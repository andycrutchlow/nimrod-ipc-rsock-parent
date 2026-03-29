package com.nimrodtechs.ipcrsock.processor;

import com.nimrodtechs.ipcrsock.annotations.*;
import com.squareup.javapoet.*;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Generates both server-side @MessageMapping controllers and
 * client-side proxy classes for @NimrodRmiInterface definitions.
 */
@SupportedAnnotationTypes("com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class NimrodRmiProcessor extends AbstractProcessor {

    private static final TypeName MONO = ClassName.get("reactor.core.publisher", "Mono");
    private static final TypeName VOID = ClassName.get(Void.class);
    private static final ClassName DURATION = ClassName.get("java.time", "Duration");

    private Filer filer;
    private Messager messager;
    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        filer = env.getFiler();
        messager = env.getMessager();
        elements = env.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) return false;

        for (Element iface : roundEnv.getElementsAnnotatedWith(NimrodRmiInterface.class)) {
            if (!(iface instanceof TypeElement interfaceType)) continue;
            try {
                NimrodRmiInterface ann = interfaceType.getAnnotation(NimrodRmiInterface.class);

                String serviceName = ann.serviceName();
                int concurrency = ann.concurrency();
                SchedulerType schedulerType = ann.scheduler();
                long timeoutMs = ann.timeoutMs();
                RetryPolicy retryPolicy = ann.retryPolicy(); // unused for now

                generateServerController(interfaceType, serviceName, concurrency, schedulerType, timeoutMs, retryPolicy);
                generateClientProxy(interfaceType, serviceName);
            } catch (Exception e) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Failed to process " + iface.getSimpleName() + ": " + e.getMessage(),
                        iface
                );
            }
        }
        return true;
    }

    // =============================================================================================
    // SERVER SIDE CONTROLLER
    // =============================================================================================
    private void generateServerController(
            TypeElement iface,
            String prefix,
            int concurrency,
            SchedulerType schedulerType,
            long timeoutMs,
            RetryPolicy retryPolicy
    ) throws Exception {
        String pkg = elements.getPackageOf(iface).getQualifiedName().toString();
        String ifaceName = iface.getSimpleName().toString();
        String generatedName = ifaceName + "__NimrodRmiController";

        TypeSpec.Builder controller = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Controller.class)
                .addAnnotation(AnnotationSpec.builder(
                                ClassName.get("org.springframework.context.annotation", "Conditional"))
                        .addMember("value", "$T.class",
                                ClassName.get("com.nimrodtechs.ipcrsock.spring", "NimrodServerEnabledCondition"))
                        .build())
                .addJavadoc("Auto-generated controller for $L", ifaceName);

        // The controller depends on an implementation of the interface
        controller.addField(TypeName.get(iface.asType()), "service", Modifier.PRIVATE, Modifier.FINAL);
        controller.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(iface.asType()), "service")
                .addStatement("this.service = service")
                .build());

        // Optional service-level scheduler (only generated when needed)
        boolean needsScheduler = (concurrency > 1) || (schedulerType != SchedulerType.SINGLE);

        if (needsScheduler) {
            CodeBlock schedulerInit = null;

            switch (schedulerType) {
                case PARALLEL -> schedulerInit = CodeBlock.of(
                        "$T.newParallel($S, $L)",
                        ClassName.get("reactor.core.scheduler", "Schedulers"),
                        prefix, concurrency
                );
                case BOUNDED_ELASTIC -> schedulerInit = CodeBlock.of(
                        "$T.boundedElastic()",
                        ClassName.get("reactor.core.scheduler", "Schedulers")
                );
                case SINGLE -> {
                    // Explicit SINGLE: preserve current semantics by not generating a scheduler.
                    schedulerInit = null;
                }
            }

            if (schedulerInit != null) {
                controller.addField(FieldSpec.builder(
                                ClassName.get("reactor.core.scheduler", "Scheduler"),
                                "SERVICE_SCHEDULER",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL
                        )
                        .initializer(schedulerInit)
                        .build());
            }
        }

        String routeBase = (prefix == null || prefix.isEmpty()) ? "" : prefix + ".";

        // Iterate over interface methods
        for (Element e : iface.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;

            ExecutableElement m = (ExecutableElement) e;
            CallSemantics semantics = resolveSemantics(m);
            List<? extends VariableElement> params = m.getParameters();
            TypeMirror returnType = m.getReturnType();

            // Build argument unpacking block
            CodeBlock.Builder code = CodeBlock.builder();
            code.addStatement("final Object[] args = (params == null ? new Object[0] : params)");

            for (int i = 0; i < params.size(); i++) {
                TypeMirror tm = params.get(i).asType();
                code.addStatement("$T p$L = ($T) args[$L]", tm, i, tm, i);
            }

            // Build param list
            StringJoiner callArgs = new StringJoiner(", ");
            for (int i = 0; i < params.size(); i++) {
                callArgs.add("p" + i);
            }

            String route = m.getSimpleName().toString();

            // Determine the correct reactive return type, e.g. Mono<String>, Mono<MyPojo>, etc.
            TypeName monoReturn;

            if (returnType.getKind() == TypeKind.VOID) {
                monoReturn = ParameterizedTypeName.get(
                        ClassName.get("reactor.core.publisher", "Mono"),
                        ClassName.get(Void.class)
                );
            } else {
                monoReturn = ParameterizedTypeName.get(
                        ClassName.get("reactor.core.publisher", "Mono"),
                        TypeName.get(returnType)
                );
            }

            // =====================================================================================
            // FIRE AND FORGET HANDLER — virtual thread with logging
            // =====================================================================================
            if (semantics == CallSemantics.FIRE_AND_FORGET) {

                if (returnType.getKind() != TypeKind.VOID) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@NimrodFireAndForget method must return void", m);
                }

                code.add("Thread.startVirtualThread(() -> {\n");
                code.add("    try {\n");
                code.add("        service.$L($L);\n", m.getSimpleName(), callArgs.toString());
                code.add("    } catch (Exception ex) {\n");
                code.add("        System.err.println(\"Exception in @NimrodFireAndForget method $L: \" + ex.getMessage());\n",
                        m.getSimpleName());
                code.add("        ex.printStackTrace();\n");
                code.add("    }\n");
                code.add("});\n");

                code.addStatement("return $T.empty()", MONO);
            }

            // =====================================================================================
            // REQUEST/RESPONSE — normal exception propagation
            // =====================================================================================
            else {
                boolean needsSubscribeOn = (concurrency > 1) || (schedulerType != SchedulerType.SINGLE);
                boolean needsTimeout = timeoutMs > 0;

                if (returnType.getKind() == TypeKind.VOID) {

                    // Mono<Void> base
                    code.add("return $T.fromRunnable(() -> service.$L($L))",
                            MONO, m.getSimpleName(), callArgs.toString());

                } else {

                    // Mono<T> base (null => empty, matches justOrEmpty behaviour)
                    code.add("return $T.fromCallable(() -> service.$L($L))",
                            MONO, m.getSimpleName(), callArgs.toString());
                }

                if (needsSubscribeOn && (schedulerType != SchedulerType.SINGLE)) {
                    // Only subscribeOn when we actually generated a scheduler
                    code.add("\n    .subscribeOn(SERVICE_SCHEDULER)");
                }

                if (needsTimeout) {
                    code.add("\n    .timeout($T.ofMillis($L))", DURATION, timeoutMs);
                }

                code.addStatement("");
            }


            // Build method
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(m.getSimpleName().toString())
                    .addAnnotation(AnnotationSpec.builder(MessageMapping.class)
                            .addMember("value", "$S", route)
                            .build())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(monoReturn)
                    .addParameter(Object[].class, "params")
                    .addCode(code.build());

            // RR only: handler declares throws Exception
            if (semantics == CallSemantics.REQUEST_RESPONSE) {
                methodBuilder.addException(Exception.class);
            }

            controller.addMethod(methodBuilder.build());
        }

        // Output file
        JavaFile javaFile = JavaFile.builder(pkg, controller.build())
                .indent("    ")
                .build();

        JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, iface);
        try (Writer w = jfo.openWriter()) {
            javaFile.writeTo(w);
        }
    }

    // =============================================================================================
    // CLIENT SIDE PROXY
    // =============================================================================================
    private void generateClientProxy(TypeElement iface, String prefix) throws Exception {
        String pkg = elements.getPackageOf(iface).getQualifiedName().toString();
        String ifaceName = iface.getSimpleName().toString();
        String generatedName = ifaceName + "__NimrodRmiClient";

        TypeSpec.Builder proxy = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(TypeName.get(iface.asType()))
                .addJavadoc("Auto-generated RMI client proxy for $L", ifaceName)
                .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"))
                .addAnnotation(AnnotationSpec.builder(
                                ClassName.get("org.springframework.context.annotation", "Conditional"))
                        .addMember("value", "$T.class",
                                ClassName.get("com.nimrodtechs.ipcrsock.spring", "NimrodClientEnabledCondition"))
                        .build())
                .addField(ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"),
                        "remoteServerService", Modifier.PRIVATE, Modifier.FINAL);


        // Hard-code serviceName from the annotation
        proxy.addField(FieldSpec.builder(String.class, "serviceName", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$S", prefix)
                .build());

        proxy.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(
                        ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"),
                        "remoteServerService")
                .addStatement("this.remoteServerService = remoteServerService")
                .build());

        //Add validation method right here
        proxy.addMethod(MethodSpec.methodBuilder("validateServerConfigured")
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addModifiers(Modifier.PUBLIC)
                .addException(Exception.class)
                .addCode("""
                        if (!remoteServerService.isServerConfigured(serviceName)) {
                            throw new IllegalStateException(
                                "NimrodRmiInterface: No matching nimrod.rsock.server.setup[] entry found for serviceName=" + serviceName);
                        }
                        """)
                .build());

        // Generate each method
        for (Element e : iface.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;

            ExecutableElement m = (ExecutableElement) e;
            CallSemantics semantics = resolveSemantics(m);

            List<? extends VariableElement> params = m.getParameters();
            TypeMirror returnType = m.getReturnType();
            String returnTypeStr = returnType.toString();

            CodeBlock.Builder body = CodeBlock.builder();
            StringJoiner paramNames = new StringJoiner(", ");
            for (VariableElement p : params) paramNames.add(p.getSimpleName().toString());

            if (semantics == CallSemantics.FIRE_AND_FORGET) {

                if (!returnTypeStr.equals("void")) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "@NimrodFireAndForget method must return void", m);
                }

                body.addStatement(
                        "remoteServerService.fireAndForget(serviceName, $S, $L)",
                        m.getSimpleName(), paramNames);
            }
            else {
                if (returnTypeStr.equals("void")) {
                    body.addStatement(
                            "remoteServerService.executeRmiMethod(Void.class, serviceName, $S, $L)",
                            m.getSimpleName(), paramNames);
                }
                else if (returnTypeStr.startsWith("java.util.List")
                        || returnTypeStr.startsWith("java.util.Set")
                        || returnTypeStr.startsWith("java.util.Collection")
                        || returnTypeStr.startsWith("java.util.Map")
                        || returnTypeStr.startsWith("java.util.HashMap")) {

                    String rawType = returnTypeStr.contains("<")
                            ? returnTypeStr.substring(0, returnTypeStr.indexOf('<'))
                            : returnTypeStr;

                    body.addStatement(
                            "return remoteServerService.executeRmiMethod($T.class, serviceName, $S, $L)",
                            ClassName.bestGuess(rawType),
                            m.getSimpleName(),
                            paramNames);
                }
                else {
                    body.addStatement(
                            "return remoteServerService.executeRmiMethod($T.class, serviceName, $S, $L)",
                            returnType,
                            m.getSimpleName(),
                            paramNames);
                }
            }

            MethodSpec.Builder method = MethodSpec.methodBuilder(m.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(TypeName.get(returnType))
                    .addParameters(
                            params.stream().map(p ->
                                    ParameterSpec.builder(TypeName.get(p.asType()),
                                            p.getSimpleName().toString()).build()
                            ).toList())
                    .addCode(body.build());

            // IMPORTANT: all client-side methods can throw Exception (RR + FNF)
            method.addException(Exception.class);

            proxy.addMethod(method.build());
        }

        // Output file
        JavaFile javaFile = JavaFile.builder(pkg, proxy.build())
                .indent("    ")
                .build();

        JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, iface);
        try (Writer w = jfo.openWriter()) {
            javaFile.writeTo(w);
        }
    }

    // =============================================================================================
    // SEMANTICS RESOLUTION
    // =============================================================================================
    private enum CallSemantics {
        FIRE_AND_FORGET,
        REQUEST_RESPONSE
    }

    private CallSemantics resolveSemantics(ExecutableElement m) {
        boolean isFNF = m.getAnnotation(NimrodFireAndForget.class) != null;
        boolean isRR  = m.getAnnotation(NimrodRequestResponse.class) != null;

        if (isFNF && isRR) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@NimrodFireAndForget and @NimrodRequestResponse cannot both be present", m);
            return CallSemantics.REQUEST_RESPONSE;
        }

        if (isFNF) return CallSemantics.FIRE_AND_FORGET;
        return CallSemantics.REQUEST_RESPONSE;
    }
}
