package com.nimrodtechs.ipcrsock.processor;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.squareup.javapoet.*;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
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
                generateServerController(interfaceType, serviceName);
                generateClientProxy(interfaceType, serviceName);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to process " + iface.getSimpleName() + ": " + e.getMessage(), iface);
            }
        }
        return true;
    }

    // --------------------------------------------------------------------------------------------
    // SERVER SIDE CONTROLLER
    // --------------------------------------------------------------------------------------------
    private void generateServerController(TypeElement iface, String prefix) throws Exception {
        String pkg = elements.getPackageOf(iface).getQualifiedName().toString();
        String ifaceName = iface.getSimpleName().toString();
        String generatedName = ifaceName + "__NimrodRmiController";

        TypeSpec.Builder controller = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Controller.class)
                .addAnnotation(AnnotationSpec.builder(
                                ClassName.get("org.springframework.context.annotation", "Profile"))
                        .addMember("value", "{$S,$S}", "nimrod-rmi-server","default")
                        .build())
                .addJavadoc("Auto-generated controller for $L", ifaceName);

        // The controller depends on an implementation of the interface
        controller.addField(TypeName.get(iface.asType()), "service", Modifier.PRIVATE, Modifier.FINAL);
        controller.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(iface.asType()), "service")
                .addStatement("this.service = service")
                .build());

        String routeBase = (prefix == null || prefix.isEmpty()) ? "" : prefix + ".";

        for (Element e : iface.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) e;

            // Build argument unpacking
            List<? extends VariableElement> params = m.getParameters();
            CodeBlock.Builder code = CodeBlock.builder();
            code.addStatement("final Object[] args = (params == null ? new Object[0] : params)");
            for (int i = 0; i < params.size(); i++) {
                TypeMirror tm = params.get(i).asType();
                code.addStatement("$T p$L = ($T) args[$L]", tm, i, tm, i);
            }

            StringJoiner callArgs = new StringJoiner(", ");
            for (int i = 0; i < params.size(); i++) callArgs.add("p" + i);

            //String route = routeBase + m.getSimpleName().toString();
            String route = m.getSimpleName().toString();
            TypeMirror returnType = m.getReturnType();

            // Generate call and return mapping
            if (returnType.toString().equals("void")) {
                code.addStatement("service.$L($L)", m.getSimpleName(), callArgs);
                code.addStatement("return reactor.core.publisher.Mono.empty()");
            } else {
                code.addStatement("$T result = service.$L($L)", returnType, m.getSimpleName(), callArgs);
                code.addStatement("return reactor.core.publisher.Mono.justOrEmpty(result)");
            }

            // Determine the correct reactive return type, e.g. Mono<String>, Mono<MyPojo>, etc.
            TypeName monoReturn = ParameterizedTypeName.get(
                    ClassName.get("reactor.core.publisher", "Mono"),
                    TypeName.get(m.getReturnType())
            );

            controller.addMethod(MethodSpec.methodBuilder(m.getSimpleName().toString())
                    .addAnnotation(AnnotationSpec.builder(MessageMapping.class)
                            .addMember("value", "$S", route)
                            .build())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(monoReturn)
                    .addParameter(Object[].class, "params")
                    .addException(Exception.class)
                    .addCode(code.build())
                    .build());
        }

        JavaFile javaFile = JavaFile.builder(pkg, controller.build())
                .indent("    ")
                .build();

        JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, iface);
        try (Writer w = jfo.openWriter()) {
            javaFile.writeTo(w);
        }
    }

    // --------------------------------------------------------------------------------------------
    // CLIENT SIDE PROXY
    // --------------------------------------------------------------------------------------------
//    private void generateClientProxy(TypeElement iface, String prefix) throws Exception {
//        String pkg = elements.getPackageOf(iface).getQualifiedName().toString();
//        String ifaceName = iface.getSimpleName().toString();
//        String generatedName = ifaceName + "__NimrodRmiClient";
//
//        TypeSpec.Builder proxy = TypeSpec.classBuilder(generatedName)
//                .addModifiers(Modifier.PUBLIC)
//                .addSuperinterface(TypeName.get(iface.asType()))
//                .addJavadoc("Auto-generated RMI client proxy for $L", ifaceName)
//                .addAnnotation(ClassName.get("org.springframework.stereotype", "Service"))
//                .addAnnotation(AnnotationSpec.builder(
//                                ClassName.get("org.springframework.context.annotation", "Profile"))
//                        .addMember("value", "{$S,$S}", "nimrod-rmi-client","default")
//                        .build())
//                .addField(ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"),
//                        "remoteServerService", Modifier.PRIVATE, Modifier.FINAL);
//
//        // Hard-code serviceName from the annotation
//        proxy.addField(FieldSpec.builder(String.class, "serviceName", Modifier.PRIVATE, Modifier.FINAL)
//                .initializer("$S", prefix)
//                .build());
//
//        proxy.addMethod(MethodSpec.constructorBuilder()
//                .addModifiers(Modifier.PUBLIC)
//                .addParameter(ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"), "remoteServerService")
//                .addStatement("this.remoteServerService = remoteServerService")
//                .build());
//
//        for (Element e : iface.getEnclosedElements()) {
//            if (e.getKind() != ElementKind.METHOD) continue;
//            ExecutableElement m = (ExecutableElement) e;
//            List<? extends VariableElement> params = m.getParameters();
//            TypeMirror returnType = m.getReturnType();
//
//            CodeBlock.Builder body = CodeBlock.builder();
//            StringJoiner paramNames = new StringJoiner(", ");
//            for (VariableElement p : params) paramNames.add(p.getSimpleName().toString());
//
//            if (returnType.toString().equals("void")) {
//                body.addStatement("remoteServerService.fireAndForget(serviceName, $S, $L)",
//                        m.getSimpleName(), paramNames);
//            } else {
//                body.addStatement("return remoteServerService.executeRmiMethod($T.class, serviceName, $S, $L)",
//                        returnType, m.getSimpleName(), paramNames);
//            }
//
//            proxy.addMethod(MethodSpec.methodBuilder(m.getSimpleName().toString())
//                    .addModifiers(Modifier.PUBLIC)
//                    .addAnnotation(Override.class)
//                    .returns(TypeName.get(returnType))
//                    .addParameters(
//                            params.stream().map(p ->
//                                    ParameterSpec.builder(TypeName.get(p.asType()), p.getSimpleName().toString()).build()
//                            ).toList())
//                    .addException(Exception.class)
//                    .addCode(body.build())
//                    .build());
//        }
//
//        JavaFile javaFile = JavaFile.builder(pkg, proxy.build())
//                .indent("    ")
//                .build();
//
//        JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, iface);
//        try (Writer w = jfo.openWriter()) {
//            javaFile.writeTo(w);
//        }
//    }

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
                                ClassName.get("org.springframework.context.annotation", "Profile"))
                        .addMember("value", "{$S,$S}", "nimrod-rmi-client", "default")
                        .build())
                .addField(ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"),
                        "remoteServerService", Modifier.PRIVATE, Modifier.FINAL);

        // Hard-code serviceName from the annotation
        proxy.addField(FieldSpec.builder(String.class, "serviceName", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$S", prefix)
                .build());

        proxy.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("com.nimrodtechs.ipcrsock.client", "RemoteServerService"), "remoteServerService")
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

        for (Element e : iface.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) e;
            List<? extends VariableElement> params = m.getParameters();
            TypeMirror returnType = m.getReturnType();
            String returnTypeStr = returnType.toString();

            CodeBlock.Builder body = CodeBlock.builder();
            StringJoiner paramNames = new StringJoiner(", ");
            for (VariableElement p : params) paramNames.add(p.getSimpleName().toString());

            if (returnTypeStr.equals("void")) {
                body.addStatement("remoteServerService.fireAndForget(serviceName, $S, $L)",
                        m.getSimpleName(), paramNames);
            }
            // ✅ Handle Collections and Maps safely
            else if (returnTypeStr.startsWith("java.util.List")
                    || returnTypeStr.startsWith("java.util.Set")
                    || returnTypeStr.startsWith("java.util.Collection")
                    || returnTypeStr.startsWith("java.util.Map")
                    || returnTypeStr.startsWith("java.util.HashMap")) {

                // Extract raw type (List, Set, Map, etc.) before generic <...>
                String rawType = returnTypeStr.contains("<")
                        ? returnTypeStr.substring(0, returnTypeStr.indexOf('<'))
                        : returnTypeStr;

                body.addStatement("return remoteServerService.executeRmiMethod($T.class, serviceName, $S, $L)",
                        ClassName.bestGuess(rawType), m.getSimpleName(), paramNames);
            }
            else {
                // Regular POJO or primitive wrapper
                body.addStatement("return remoteServerService.executeRmiMethod($T.class, serviceName, $S, $L)",
                        returnType, m.getSimpleName(), paramNames);
            }

            proxy.addMethod(MethodSpec.methodBuilder(m.getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(TypeName.get(returnType))
                    .addParameters(
                            params.stream().map(p ->
                                    ParameterSpec.builder(TypeName.get(p.asType()), p.getSimpleName().toString()).build()
                            ).toList())
                    .addException(Exception.class)
                    .addCode(body.build())
                    .build());
        }

        JavaFile javaFile = JavaFile.builder(pkg, proxy.build())
                .indent("    ")
                .build();

        JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, iface);
        try (Writer w = jfo.openWriter()) {
            javaFile.writeTo(w);
        }
    }

}
