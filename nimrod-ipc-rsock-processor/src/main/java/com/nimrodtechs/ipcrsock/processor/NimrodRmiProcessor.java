package com.nimrodtechs.ipcrsock.processor;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmi;
import com.nimrodtechs.ipcrsock.annotations.NimrodRmiService;
import com.squareup.javapoet.*;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.MessageMapping;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * Generates RSocket @Controller classes for any service annotated with @NimrodRmiService.
 * Each @NimrodRmi method becomes a @MessageMapping route handler.
 */
@SupportedAnnotationTypes("com.nimrodtechs.ipcrsock.annotations.NimrodRmiService")
//@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class NimrodRmiProcessor extends AbstractProcessor {

    private Filer filer;
    private Messager messager;
    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        this.filer = env.getFiler();
        this.messager = env.getMessager();
        this.elements = env.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) return false;

        for (Element e : roundEnv.getElementsAnnotatedWith(NimrodRmiService.class)) {
            if (!(e instanceof TypeElement serviceType)) continue;

            NimrodRmiService serviceAnn = serviceType.getAnnotation(NimrodRmiService.class);
            String prefix = (serviceAnn != null) ? serviceAnn.prefix() : "";

            // Collect all @NimrodRmi methods
            List<ExecutableElement> rmiMethods = new ArrayList<>();
            for (Element enclosed : serviceType.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD &&
                        enclosed.getAnnotation(NimrodRmi.class) != null) {
                    rmiMethods.add((ExecutableElement) enclosed);
                }
            }

            if (rmiMethods.isEmpty()) continue;

            String pkg = elements.getPackageOf(serviceType).getQualifiedName().toString();
            String simpleServiceName = serviceType.getSimpleName().toString();
            String generatedName = simpleServiceName + "__NimrodRmiController";

            try {
                TypeSpec controller = buildControllerType(serviceType, rmiMethods, prefix, generatedName);
                JavaFile javaFile = JavaFile.builder(pkg, controller)
                        .indent("    ")
                        .build();

                JavaFileObject jfo = filer.createSourceFile(pkg + "." + generatedName, serviceType);
                try (Writer w = jfo.openWriter()) {
                    javaFile.writeTo(w);
                }

                messager.printMessage(Diagnostic.Kind.NOTE,
                        "Generated controller for @" + simpleServiceName + " → " + generatedName);

            } catch (Exception ex) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to generate controller for " + simpleServiceName + ": " + ex.getMessage(), e);
            }
        }
        return true;
    }

    /**
     * Builds a @Controller class wrapping all @NimrodRmi methods of a given service.
     */
//    private TypeSpec buildControllerType(TypeElement serviceType,
//                                         List<ExecutableElement> methods,
//                                         String prefix,
//                                         String generatedName) {
//
//        TypeSpec.Builder controllerBuilder = TypeSpec.classBuilder(generatedName)
//                .addModifiers(Modifier.PUBLIC)
//                .addAnnotation(Controller.class)
//                .addField(TypeName.get(serviceType.asType()), "service", Modifier.PRIVATE, Modifier.FINAL);
//
//        // constructor
//        MethodSpec ctor = MethodSpec.constructorBuilder()
//                .addModifiers(Modifier.PUBLIC)
//                .addParameter(TypeName.get(serviceType.asType()), "service")
//                .addStatement("this.service = service")
//                .build();
//        controllerBuilder.addMethod(ctor);
//
//        String routeBase = (prefix == null || prefix.isEmpty()) ? "" : prefix + ".";
//
//        // create one handler method per @NimrodRmi
//        for (ExecutableElement method : methods) {
//            String methodName = method.getSimpleName().toString();
//            List<? extends VariableElement> params = method.getParameters();
//
//            AnnotationSpec messageMapping = AnnotationSpec.builder(MessageMapping.class)
//                    .addMember("value", "$S", routeBase + methodName)
//                    .build();
//
//            CodeBlock.Builder body = CodeBlock.builder();
//            body.addStatement("final Object[] args = (params == null ? new Object[0] : params)");
//
//            // cast parameters
//            for (int i = 0; i < params.size(); i++) {
//                TypeMirror tm = params.get(i).asType();
//                body.addStatement("$T p$L = ($T) args[$L]", tm, i, tm, i);
//            }
//
//            // build arg list for method call
//            StringJoiner argList = new StringJoiner(", ");
//            for (int i = 0; i < params.size(); i++) argList.add("p" + i);
//
//            body.add("\n// Invoke target method and normalize return type\n");
//            body.addStatement("Object result = service.$L($L)", methodName, argList.toString());
//
//            // reactive normalization
//            body.add("""
//                if (result == null)
//                    return reactor.core.publisher.Mono.empty();
//                if (result instanceof reactor.core.publisher.Mono<?> mono)
//                    return (reactor.core.publisher.Mono<Object>) mono;
//                if (result instanceof reactor.core.publisher.Flux<?> flux)
//                    return flux.collectList().map(r -> (Object) r);
//                if (result instanceof java.util.concurrent.CompletionStage<?> cs)
//                    return reactor.core.publisher.Mono.fromFuture(cs.toCompletableFuture());
//                return reactor.core.publisher.Mono.just(result);
//                """);
//
//            MethodSpec handler = MethodSpec.methodBuilder(methodName)
//                    .addAnnotation(messageMapping)
//                    .addModifiers(Modifier.PUBLIC)
//                    .returns(ParameterizedTypeName.get(Mono.class, Object.class))
//                    .addParameter(Object[].class, "params")
//                    .addException(Exception.class)
//                    .addCode(body.build())
//                    .build();
//
//            controllerBuilder.addMethod(handler);
//        }
//
//        return controllerBuilder.build();
//    }

    private TypeSpec buildControllerType(TypeElement serviceType,
                                         List<ExecutableElement> methods,
                                         String prefix,
                                         String generatedName) {

        TypeSpec.Builder controllerBuilder = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Controller.class)
                .addField(TypeName.get(serviceType.asType()), "service", Modifier.PRIVATE, Modifier.FINAL);

        // constructor
        MethodSpec ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(serviceType.asType()), "service")
                .addStatement("this.service = service")
                .build();
        controllerBuilder.addMethod(ctor);

        String routeBase = (prefix == null || prefix.isEmpty()) ? "" : prefix + ".";

        // create one handler method per @NimrodRmi
        for (ExecutableElement method : methods) {
            String methodName = method.getSimpleName().toString();
            List<? extends VariableElement> params = method.getParameters();

            TypeMirror returnType = method.getReturnType();
            String returnTypeStr = returnType.toString();

            // Each @NimrodRmi method maps to @MessageMapping(route)
            AnnotationSpec messageMapping = AnnotationSpec.builder(MessageMapping.class)
                    .addMember("value", "$S", routeBase + methodName)
                    .build();

            CodeBlock.Builder body = CodeBlock.builder();
            body.addStatement("final Object[] args = (params == null ? new Object[0] : params)");

            // Cast each argument from Object[]
            for (int i = 0; i < params.size(); i++) {
                TypeMirror tm = params.get(i).asType();
                body.addStatement("$T p$L = ($T) args[$L]", tm, i, tm, i);
            }

            // Build comma-separated argument list
            StringJoiner argList = new StringJoiner(", ");
            for (int i = 0; i < params.size(); i++) argList.add("p" + i);

            // ---- Generate method body logic based on return type ----
            body.add("\n// Invoke target method and normalize to Mono<T>\n");

            if (returnTypeStr.equals("void")) {
                // Fire-and-forget
                body.addStatement("service.$L($L)", methodName, argList.toString());
                body.addStatement("return reactor.core.publisher.Mono.empty()");
            }
//            else if (returnTypeStr.startsWith("reactor.core.publisher.Mono")) {
//                // Already a Mono
//                body.addStatement("return service.$L($L)", methodName, argList.toString());
//            }
//            else if (returnTypeStr.startsWith("reactor.core.publisher.Flux")) {
//                // Convert Flux → Mono<List<T>>
//                body.addStatement("return service.$L($L).collectList()", methodName, argList.toString());
//            }
//            else if (returnTypeStr.startsWith("java.util.concurrent.CompletionStage")) {
//                // Async type
//                body.addStatement(
//                        "return reactor.core.publisher.Mono.fromFuture(service.$L($L).toCompletableFuture())",
//                        methodName, argList.toString());
//            }
            else {
                // Synchronous (e.g. String, AClass, List<AClass>, etc.)
                body.addStatement("$T result = service.$L($L)", returnType, methodName, argList.toString());
                body.addStatement("return reactor.core.publisher.Mono.justOrEmpty(result)");
            }

            // ---- Declare handler method signature ----
            TypeName monoReturn = ParameterizedTypeName.get(
                    ClassName.get("reactor.core.publisher", "Mono"), TypeName.get(returnType));

            MethodSpec handler = MethodSpec.methodBuilder(methodName)
                    .addAnnotation(messageMapping)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(monoReturn)
                    .addParameter(Object[].class, "params")
                    .addException(Exception.class)
                    .addCode(body.build())
                    .build();

            controllerBuilder.addMethod(handler);
        }

        return controllerBuilder.build();
    }


}
