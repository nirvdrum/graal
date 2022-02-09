/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.nativebridge.processor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class AbstractBridgeParser {

    final NativeBridgeProcessor processor;
    final Types types;
    final Elements elements;
    final AbstractTypeCache typeCache;
    private final Configuration myConfiguration;
    private final Configuration otherConfiguration;
    private Set<DeclaredType> ignoreAnnotations;
    private Set<DeclaredType> copyAnnotations;
    private boolean hasErrors;

    AbstractBridgeParser(NativeBridgeProcessor processor, AbstractTypeCache typeCache, Configuration myConfiguration, Configuration otherConfiguration) {
        this.processor = processor;
        this.types = processor.env().getTypeUtils();
        this.elements = processor.env().getElementUtils();
        this.typeCache = typeCache;
        this.myConfiguration = myConfiguration;
        this.otherConfiguration = otherConfiguration;
    }

    abstract List<TypeMirror> getExceptionHandlerTypes();

    abstract AbstractBridgeGenerator getGenerator();

    DefinitionData createDefinitionData(DeclaredType annotatedType, @SuppressWarnings("unused") AnnotationMirror annotation,
                    DeclaredType serviceType, Collection<MethodData> toGenerate, List<? extends VariableElement> annotatedTypeConstructorParams,
                    ExecutableElement dispatchAccessor, ExecutableElement receiverAccessor, ExecutableElement exceptionHandler,
                    VariableElement endPointHandle, DeclaredType jniConfig, Set<DeclaredType> annotationsToCopy) {
        return new DefinitionData(annotatedType, serviceType, toGenerate, annotatedTypeConstructorParams,
                        dispatchAccessor, receiverAccessor, exceptionHandler, endPointHandle, jniConfig, annotationsToCopy);
    }

    final DefinitionData parse(Element element) {
        hasErrors = false;
        ignoreAnnotations = new HashSet<>();
        copyAnnotations = new HashSet<>();
        Collections.addAll(ignoreAnnotations, typeCache.override, typeCache.suppressWarnings, typeCache.idempotent);
        AnnotationMirror handledAnnotation = processor.getAnnotation(element, myConfiguration.getHandledAnnotationType());
        checkAnnotatedType(element, handledAnnotation);
        if (hasErrors) {
            // Fatal error, parsing cannot continue.
            return null;
        }
        TypeElement annotatedElement = (TypeElement) element;
        DeclaredType annotatedType = (DeclaredType) annotatedElement.asType();

        DeclaredType serviceType = findServiceType(annotatedElement, handledAnnotation, myConfiguration.getProxyBaseType());
        if (serviceType == null) {
            // Fatal error, parsing cannot continue.
            return null;
        }

        readAnnotationActions(element);
        DeclaredType jniConfig = findJNIConfig(annotatedElement, handledAnnotation);
        List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(annotatedElement));
        ExecutableElement dispatchAccessor = dispatchAccessorIn(methods, serviceType);
        ExecutableElement receiverAccessor = receiverAccessorIn(methods, dispatchAccessor, serviceType);
        factoryMethodIn(methods, true, dispatchAccessor);
        ExecutableElement exceptionHandler = exceptionHandlerIn(methods);
        VariableElement endPointHandle = endPointHandleIn(ElementFilter.fieldsIn(elements.getAllMembers(annotatedElement)), myConfiguration.getProxyBaseType());
        boolean explicitReceiver = dispatchAccessor != null || receiverAccessor != null;
        if (!explicitReceiver && endPointHandle == null && !types.isSubtype(annotatedElement.getSuperclass(), myConfiguration.getProxyBaseType())) {
            Set<Modifier> staticMods = Collections.singleton(Modifier.STATIC);
            List<Map.Entry<? extends TypeMirror, ? extends CharSequence>> objectParam = Collections.singletonList(new SimpleImmutableEntry<>(typeCache.object, "receiver"));
            emitError(element, handledAnnotation, "The annotated type must extend `%s`, have a field annotated by `EndPointHandle` or an explicit receiver.%n" +
                            "To bridge an interface extend `%s` and implement the interface.%n" +
                            "To bridge a class extend the class, add `@%s %s` field and initialize it in the constructor.%n" +
                            "To bridge a class with an explicit receiver add `@%s %s` and `@%s %s` methods.",
                            Utilities.getTypeName(myConfiguration.getProxyBaseType()), Utilities.getTypeName(myConfiguration.getProxyBaseType()),
                            Utilities.getTypeName(typeCache.endPointHandle), Utilities.printField(Collections.singleton(Modifier.FINAL), "delegate", myConfiguration.getProxyBaseType()),
                            Utilities.getTypeName(typeCache.dispatchResolver), Utilities.printMethod(staticMods, "resolveDispatch", serviceType, objectParam),
                            Utilities.getTypeName(typeCache.receiverResolver), Utilities.printMethod(staticMods, "resolveReceiver", typeCache.object, objectParam));
        }
        ConstructorSelector constructorSelector;
        if (explicitReceiver) {
            constructorSelector = ConstructorSelector.singleConstructor(this, annotatedElement, handledAnnotation);
        } else if (endPointHandle != null) {
            constructorSelector = ConstructorSelector.withParameters(this, annotatedElement, handledAnnotation, annotatedElement, types, myConfiguration.getHandleReferenceConstructorTypes(), false);
        } else {
            constructorSelector = ConstructorSelector.withParameters(this, annotatedElement, handledAnnotation, annotatedElement, types, myConfiguration.getSubClassReferenceConstructorTypes(), false);
        }
        List<? extends VariableElement> constructorParams = findConstructorParams(annotatedType, constructorSelector);
        Collection<MethodData> toGenerate = createMethodData(annotatedType, serviceType, methods, explicitReceiver,
                        processor.getAnnotation(annotatedElement, typeCache.idempotent) != null);
        if (hasErrors) {
            return null;
        } else {
            DefinitionData definitionData = createDefinitionData(annotatedType, handledAnnotation, serviceType, toGenerate, constructorParams, dispatchAccessor,
                            receiverAccessor, exceptionHandler, endPointHandle, jniConfig, copyAnnotations);
            assertNoExpectedErrors(definitionData);
            return definitionData;
        }
    }

    static Object getAnnotationValue(AnnotationMirror mirror, String elementName) {
        ExecutableElement element = resolveElement(mirror, elementName);
        if (element == null) {
            throw new IllegalArgumentException("The " + elementName + " is not a valid annotation attribute.");
        }
        AnnotationValue annotationValue = mirror.getElementValues().get(element);
        return annotationValue == null ? null : annotationValue.getValue();
    }

    Object getAnnotationValueWithDefaults(AnnotationMirror mirror, String elementName) {
        ExecutableElement element = resolveElement(mirror, elementName);
        if (element == null) {
            throw new IllegalArgumentException("The " + elementName + " is not a valid annotation attribute.");
        }
        AnnotationValue annotationValue = elements.getElementValuesWithDefaults(mirror).get(element);
        return annotationValue == null ? null : annotationValue.getValue();
    }

    private static ExecutableElement resolveElement(AnnotationMirror mirror, String elementName) {
        List<ExecutableElement> methods = ElementFilter.methodsIn(mirror.getAnnotationType().asElement().getEnclosedElements());
        for (ExecutableElement method : methods) {
            if (method.getSimpleName().contentEquals(elementName)) {
                return method;
            }
        }
        return null;
    }

    private void readAnnotationActions(Element element) {
        Set<DeclaredType> handledAnnotations = new HashSet<>(ignoreAnnotations);
        for (AnnotationAction annotationAction : getAnnotationActions(element)) {
            if (Utilities.contains(handledAnnotations, annotationAction.annotation, types)) {
                String actionDisplayName;
                if (Utilities.contains(copyAnnotations, annotationAction.annotation, types)) {
                    actionDisplayName = "copied";
                } else if (Utilities.contains(ignoreAnnotations, annotationAction.annotation, types)) {
                    actionDisplayName = "ignored";
                } else {
                    actionDisplayName = "used for marshaller lookup";
                }
                CharSequence annotationSimpleName = annotationAction.annotation.asElement().getSimpleName();
                emitError(element, annotationAction.source, "The annotation `%s` is already configured to be %s.%n" +
                                "Remove repeating `%s` for `%s`.", annotationSimpleName, actionDisplayName,
                                typeCache.annotationAction.asElement().getSimpleName(), annotationSimpleName);
                continue;
            }
            handledAnnotations.add(annotationAction.annotation);
            switch (annotationAction.action) {
                case "IGNORE":
                    ignoreAnnotations.add(annotationAction.annotation);
                    break;
                case "COPY":
                    ignoreAnnotations.add(annotationAction.annotation);
                    copyAnnotations.add(annotationAction.annotation);
                    break;
            }
        }
    }

    private List<AnnotationAction> getAnnotationActions(Element element) {
        List<AnnotationAction> result = new ArrayList<>();
        AnnotationMirror annotationAction = processor.getAnnotation(element, typeCache.annotationAction);
        if (annotationAction != null) {
            result.add(AnnotationAction.create(annotationAction));
        }
        AnnotationMirror annotationActionRepeated = processor.getAnnotation(element, typeCache.getAnnotationActionRepeated);
        if (annotationActionRepeated != null) {
            Object values = getAnnotationValue(annotationActionRepeated, "value");
            if (values instanceof List<?>) {
                for (Object value : (List<?>) values) {
                    if (value instanceof AnnotationValue) {
                        result.add(AnnotationAction.create((AnnotationMirror) ((AnnotationValue) value).getValue()));
                    }
                }
            }
        }
        return result;
    }

    private DeclaredType findJNIConfig(Element element, AnnotationMirror handledAnnotation) {
        DeclaredType jniConfigType = (DeclaredType) getAnnotationValue(handledAnnotation, "jniConfig");
        ExecutableElement getInstanceMethod = null;
        for (ExecutableElement executableElement : ElementFilter.methodsIn(jniConfigType.asElement().getEnclosedElements())) {
            Set<Modifier> modifiers = executableElement.getModifiers();
            if (!modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) {
                continue;
            }
            if (!"getInstance".contentEquals(executableElement.getSimpleName())) {
                continue;
            }
            if (!executableElement.getParameters().isEmpty()) {
                continue;
            }
            if (!types.isSameType(typeCache.jniConfig, executableElement.getReturnType())) {
                continue;
            }
            getInstanceMethod = executableElement;
        }
        if (getInstanceMethod == null) {
            emitError(element, handledAnnotation, "JNI config must have a non private static `getInstance()` method returning `JNIConfig`.%n" +
                            "Add the `static JNIConfig getInstance() { return INSTANCE;}` into `%s`.",
                            jniConfigType.asElement().getSimpleName());
        }
        return jniConfigType;
    }

    private ExecutableElement dispatchAccessorIn(Iterable<? extends ExecutableElement> methods, DeclaredType serviceType) {
        ExecutableElement res = null;
        AnnotationMirror annotation = null;
        for (ExecutableElement method : methods) {
            AnnotationMirror dispatchResolver = processor.getAnnotation(method, typeCache.dispatchResolver);
            if (dispatchResolver != null) {
                if (res != null) {
                    emitError(method, dispatchResolver, "Only single method can be annotated by `%s`.%n" +
                                    "Remove `%s` or `%s` method.",
                                    Utilities.getTypeName(typeCache.dispatchResolver), Utilities.printMethod(res), Utilities.printMethod(method));
                    break;
                } else {
                    res = method;
                    annotation = dispatchResolver;
                }
            }
        }
        if (res != null) {
            if (!res.getModifiers().contains(Modifier.STATIC) || res.getModifiers().contains(Modifier.PRIVATE) || res.getParameters().size() != 1 ||
                            !types.isSameType(serviceType, res.getReturnType())) {
                Set<Modifier> expectedModifiers = staticNonPrivate(res.getModifiers());
                List<Map.Entry<TypeMirror, CharSequence>> expectedParameters;
                if (res.getParameters().size() == 1) {
                    VariableElement parameter = res.getParameters().get(0);
                    expectedParameters = Collections.singletonList(new SimpleImmutableEntry<>(parameter.asType(), parameter.getSimpleName()));
                } else {
                    expectedParameters = Collections.singletonList(new SimpleImmutableEntry<>(typeCache.object, "receiver"));
                }
                emitError(res, annotation, "Method annotated by `%s` must be a non private static method with a single parameter and `%s` return type.%n" +
                                "Change signature to `%s`.", Utilities.getTypeName(typeCache.dispatchResolver), Utilities.getTypeName(serviceType),
                                Utilities.printMethod(expectedModifiers, res.getSimpleName(), serviceType, expectedParameters));
            }
        }
        return res;
    }

    private ExecutableElement receiverAccessorIn(Iterable<? extends ExecutableElement> methods, ExecutableElement dispatchReceiver, DeclaredType serviceType) {
        ExecutableElement res = null;
        AnnotationMirror annotation = null;
        for (ExecutableElement method : methods) {
            AnnotationMirror receiverResolver = processor.getAnnotation(method, typeCache.receiverResolver);
            if (receiverResolver != null) {
                if (res != null) {
                    emitError(method, receiverResolver, "Only single method can be annotated by `%s`.%n" +
                                    "Remove `%s` or `%s` method.",
                                    Utilities.getTypeName(typeCache.receiverResolver), Utilities.printMethod(res), Utilities.printMethod(method));
                    break;
                } else {
                    res = method;
                    annotation = receiverResolver;
                }
            }
        }
        if (res != null) {
            if (!res.getModifiers().contains(Modifier.STATIC) || res.getModifiers().contains(Modifier.PRIVATE) || res.getParameters().size() != 1 ||
                            !types.isSubtype(typeCache.object, types.erasure(res.getReturnType()))) {
                Set<Modifier> expectedModifiers = staticNonPrivate(res.getModifiers());
                List<Map.Entry<TypeMirror, CharSequence>> expectedParameters;
                if (res.getParameters().size() == 1) {
                    VariableElement parameter = res.getParameters().get(0);
                    expectedParameters = Collections.singletonList(new SimpleImmutableEntry<>(parameter.asType(), parameter.getSimpleName()));
                } else {
                    expectedParameters = Collections.singletonList(new SimpleImmutableEntry<>(typeCache.object, "receiver"));
                }
                emitError(res, annotation, "Method annotated by `%s` must be a non private non void static method with a single parameter.%n" +
                                "Change signature to `%s`.", Utilities.getTypeName(typeCache.receiverResolver),
                                Utilities.printMethod(expectedModifiers, res.getSimpleName(), typeCache.object, expectedParameters));
            }

            if (dispatchReceiver == null) {
                emitError(res, annotation, "Class with a receiver resolver method must also provide dispatch resolver method.%n" +
                                "Add `@%s %s` method.", Utilities.getTypeName(typeCache.dispatchResolver),
                                Utilities.printMethod(staticNonPrivate(Collections.emptySet()), "resolveDispatch", serviceType, res.getParameters().toArray(new VariableElement[0])));
            } else if (dispatchReceiver.getParameters().size() == 1 && res.getParameters().size() == 1) {
                if (!types.isSameType(dispatchReceiver.getParameters().get(0).asType(), res.getParameters().get(0).asType())) {
                    emitError(res, annotation, "Receiver resolver method must have the same parameter type as the dispatch resolver method.");
                }
            }
        } else if (dispatchReceiver != null) {
            AnnotationMirror dispatchResolver = processor.getAnnotation(dispatchReceiver, typeCache.dispatchResolver);
            emitError(dispatchReceiver, dispatchResolver, "Class with a dispatch resolver method must also provide receiver resolver method.%n" +
                            "Add `@%s %s` method.", Utilities.getTypeName(typeCache.receiverResolver),
                            Utilities.printMethod(staticNonPrivate(Collections.emptySet()), "resolveReceiver", typeCache.object, dispatchReceiver.getParameters().toArray(new VariableElement[0])));
        }
        return res;
    }

    private ExecutableElement exceptionHandlerIn(Iterable<? extends ExecutableElement> methods) {
        ExecutableElement res = null;
        AnnotationMirror annotation = null;
        for (ExecutableElement method : methods) {
            AnnotationMirror exceptionHandler = processor.getAnnotation(method, typeCache.exceptionHandler);
            if (exceptionHandler != null) {
                if (res != null) {
                    emitError(method, exceptionHandler, "Only single method can be annotated by `%s`.%n" +
                                    "Remove `%s` or `%s` method.",
                                    Utilities.getTypeName(typeCache.exceptionHandler), Utilities.printMethod(res), Utilities.printMethod(method));
                    break;
                } else {
                    res = method;
                    annotation = exceptionHandler;
                }
            }
        }
        if (res != null) {
            List<? extends VariableElement> parameters = res.getParameters();
            List<? extends TypeMirror> requiredTypes = getExceptionHandlerTypes();
            boolean validParameterTypes = false;
            if (parameters.size() == requiredTypes.size()) {
                validParameterTypes = true;
                for (int i = 0; i < parameters.size(); i++) {
                    if (!types.isSameType(parameters.get(i).asType(), requiredTypes.get(i))) {
                        validParameterTypes = false;
                        break;
                    }
                }
            }
            if (!res.getModifiers().contains(Modifier.STATIC) || res.getModifiers().contains(Modifier.PRIVATE) ||
                            !types.isSameType(types.getPrimitiveType(TypeKind.BOOLEAN), res.getReturnType()) || !validParameterTypes) {
                Set<Modifier> expectedModifiers = staticNonPrivate(res.getModifiers());
                List<Map.Entry<? extends TypeMirror, ? extends CharSequence>> expectedParameters;
                if (validParameterTypes) {
                    expectedParameters = parameters.stream().map((ve) -> new SimpleImmutableEntry<>(ve.asType(), ve.getSimpleName())).collect(Collectors.toList());
                } else {
                    expectedParameters = new ArrayList<>(requiredTypes.size());
                    int i = 0;
                    for (TypeMirror tm : requiredTypes) {
                        expectedParameters.add(new SimpleImmutableEntry<>(tm, "p" + i++));
                    }
                }
                emitError(res, annotation, "Method annotated by `%s` must be a non private static boolean method with %s parameter(s).%n" +
                                "Change signature to `%s`.", Utilities.getTypeName(typeCache.exceptionHandler),
                                requiredTypes.stream().map(Utilities::getTypeName).map((s) -> "`" + s + "`").collect(Collectors.joining(", ")),
                                Utilities.printMethod(expectedModifiers, res.getSimpleName(),
                                                types.getPrimitiveType(TypeKind.BOOLEAN), expectedParameters));

            }
        }
        return res;
    }

    private VariableElement endPointHandleIn(Iterable<? extends VariableElement> fields, DeclaredType baseType) {
        VariableElement res = null;
        AnnotationMirror annotation = null;
        for (VariableElement field : fields) {
            AnnotationMirror endPointHandle = processor.getAnnotation(field, typeCache.endPointHandle);
            if (endPointHandle != null) {
                if (res != null) {
                    emitError(field, endPointHandle, "Only single field can be annotated by `%s`.%n" +
                                    "Remove `%s` or `%s` field.",
                                    Utilities.getTypeName(typeCache.endPointHandle), Utilities.printField(res), Utilities.printField(field));
                    break;
                } else {
                    res = field;
                    annotation = endPointHandle;
                }
            }
        }
        if (res != null) {
            if (res.getModifiers().contains(Modifier.PRIVATE) || !types.isSubtype(res.asType(), baseType)) {
                Set<Modifier> expectedModifiers = EnumSet.noneOf(Modifier.class);
                expectedModifiers.addAll(res.getModifiers());
                // Remove private if present
                expectedModifiers.remove(Modifier.PRIVATE);
                emitError(res, annotation, "Field annotated by `%s` must be a non private field of `%s` type.%n" +
                                "Change signature to `%s`.", Utilities.getTypeName(typeCache.endPointHandle),
                                Utilities.getTypeName(baseType),
                                Utilities.printField(expectedModifiers, res.getSimpleName(), baseType));
            }
        }
        return res;
    }

    private ExecutableElement factoryMethodIn(Iterable<? extends ExecutableElement> methods, boolean verify, ExecutableElement dispatchAccessor) {
        ExecutableElement res = null;
        AnnotationMirror annotation = null;
        for (ExecutableElement method : methods) {
            AnnotationMirror factory = processor.getAnnotation(method, typeCache.factory);
            if (factory != null) {
                if (verify && res != null) {
                    emitError(method, factory, "Only single method can be annotated by `%s`.%n" +
                                    "Remove `%s` or `%s` method.",
                                    Utilities.getTypeName(typeCache.factory), Utilities.printMethod(res), Utilities.printMethod(method));
                    break;
                } else {
                    res = method;
                    annotation = factory;
                }
            }
        }
        if (verify && res != null) {
            if (dispatchAccessor == null) {
                emitError(res, annotation, "Method annotated by `%s` is allowed only for classes with an explicit receiver.%n" +
                                "Add dispatch resolver method annotated by `%s` and receiver resolver method annotated by `%s`.",
                                Utilities.getTypeName(typeCache.factory), Utilities.getTypeName(typeCache.dispatchResolver), Utilities.getTypeName(typeCache.receiverResolver));
            } else {
                List<? extends VariableElement> dispatchAccessorParams = dispatchAccessor.getParameters();
                TypeMirror expectedReturnType = dispatchAccessorParams.size() == 1 ? dispatchAccessorParams.get(0).asType() : typeCache.object;
                if (!res.getModifiers().contains(Modifier.STATIC) || res.getModifiers().contains(Modifier.PRIVATE) || res.getParameters().size() != 1 ||
                                !types.isSubtype(res.getReturnType(), expectedReturnType)) {
                    Set<Modifier> expectedModifiers = staticNonPrivate(res.getModifiers());
                    List<Map.Entry<TypeMirror, CharSequence>> expectedParameters = Collections.singletonList(new SimpleImmutableEntry<>(typeCache.object, "receiver"));
                    emitError(res, annotation, "Method annotated by `%s` must be a non private static method with a single object parameter and `%s` return type.%n" +
                                    "Change signature to `%s`.", Utilities.getTypeName(typeCache.factory), Utilities.getTypeName(expectedReturnType),
                                    Utilities.printMethod(expectedModifiers, res.getSimpleName(), expectedReturnType, expectedParameters));
                }
            }
        }
        return res;
    }

    private DeclaredType findServiceType(TypeElement typeElement, AnnotationMirror annotation, DeclaredType proxyType) {
        TypeMirror superClass = typeElement.getSuperclass();
        if (superClass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        String fix;
        List<? extends TypeMirror> interfaces = typeElement.getInterfaces();
        if (!types.isSubtype(superClass, proxyType) && !types.isSameType(superClass, typeCache.object)) {
            if (interfaces.isEmpty()) {
                return (DeclaredType) superClass;
            } else {
                Stream<CharSequence> toImplement = interfaces.stream().filter((tm) -> tm.getKind() == TypeKind.DECLARED).map((tm) -> ((DeclaredType) tm).asElement().getSimpleName());
                fix = String.format("Introduce a new bridged base class extending `%s` and implementing %s and extend it.",
                                ((DeclaredType) superClass).asElement().getSimpleName(), toImplement.map((s) -> "`" + s + "`").collect(Collectors.joining(", ")));
            }
        } else {
            switch (interfaces.size()) {
                case 0:
                    fix = "Implement the bridged interface or extend the bridged class.";
                    break;
                case 1:
                    return (DeclaredType) interfaces.get(0);
                default:
                    Stream<CharSequence> toImplement = interfaces.stream().filter((tm) -> tm.getKind() == TypeKind.DECLARED).map((tm) -> ((DeclaredType) tm).asElement().getSimpleName());
                    fix = String.format("Introduce a new bridged interface extending %s and implement it.",
                                    toImplement.map((s) -> "`" + s + "`").collect(Collectors.joining(", ")));
            }
        }
        emitError(typeElement, annotation, "The annotated type must have a non `Object` super class or implement a single interface.%n" + fix);
        return null;
    }

    private Collection<MethodData> createMethodData(DeclaredType annotatedType, DeclaredType serviceType, List<ExecutableElement> methods,
                    boolean explicitReceiver, boolean enforceIdempotent) {
        Collection<ExecutableElement> methodsToGenerate = methodsToGenerate(annotatedType, methods);
        for (ExecutableElement methodToGenerate : methodsToGenerate) {
            if (methodToGenerate.getEnclosingElement().equals(annotatedType.asElement()) && !methodToGenerate.getModifiers().contains(Modifier.ABSTRACT)) {
                emitError(methodToGenerate, null, "Should be `final` to prevent override in the generated class or `abstract` to be generated.%n" +
                                "Add a `final` modifier or remove implementation in the `%s`.", Utilities.getTypeName(annotatedType));
            }
        }
        Set<? extends CharSequence> overloadedMethods = methodsToGenerate.stream().collect(Collectors.toMap(ExecutableElement::getSimpleName, (e) -> 1, (l, r) -> l + r)).entrySet().stream().filter(
                        (e) -> e.getValue() > 1).map((e) -> e.getKey()).collect(Collectors.toSet());
        Map<CharSequence, Integer> simpleNameCounter = new HashMap<>();
        Map<ExecutableElement, Integer> overloadIds = new HashMap<>();
        for (ExecutableElement methodToGenerate : methodsToGenerate) {
            CharSequence simpleName = methodToGenerate.getSimpleName();
            int index = overloadedMethods.contains(simpleName) ? simpleNameCounter.compute(simpleName, (id, prev) -> prev == null ? 1 : prev + 1) : 0;
            overloadIds.put(methodToGenerate, index);
        }
        Collection<MethodData> toGenerate = new ArrayList<>(methodsToGenerate.size());
        Set<String> usedCacheFields = new HashSet<>();
        for (ExecutableElement methodToGenerate : methodsToGenerate) {
            ExecutableType methodToGenerateType = (ExecutableType) types.asMemberOf(annotatedType, methodToGenerate);
            MarshallerData retMarshaller = lookupMarshaller(methodToGenerate, methodToGenerateType.getReturnType(), methodToGenerate.getAnnotationMirrors(), explicitReceiver);
            List<? extends VariableElement> parameters = methodToGenerate.getParameters();
            List<? extends TypeMirror> parameterTypes = methodToGenerateType.getParameterTypes();
            List<MarshallerData> paramsMarshallers = new ArrayList<>();
            if (explicitReceiver) {
                if (parameters.isEmpty() || types.erasure(parameterTypes.get(0)).getKind() != TypeKind.DECLARED) {
                    emitError(methodToGenerate, null, "In a class with an explicit receiver the first method parameter must be a receiver.%n" +
                                    "For class with an explicit receiver make the method `final` to prevent its generation.%n" +
                                    "For class which has no explicit receiver remove methods annotated by `%s` and `%s`.",
                                    Utilities.getTypeName(typeCache.dispatchResolver), Utilities.getTypeName(typeCache.receiverResolver));
                } else {
                    paramsMarshallers.add(MarshallerData.NO_MARSHALLER);
                    parameters = parameters.subList(1, parameters.size());
                    parameterTypes = parameterTypes.subList(1, parameterTypes.size());
                }
            }
            paramsMarshallers.addAll(lookupMarshallers(methodToGenerate, parameters, parameterTypes, explicitReceiver));
            CacheData cacheData;
            AnnotationMirror idempotentMirror = processor.getAnnotation(methodToGenerate, typeCache.idempotent);
            boolean noReturnType = methodToGenerateType.getReturnType().getKind() == TypeKind.VOID;
            if (idempotentMirror != null && noReturnType) {
                emitError(methodToGenerate, idempotentMirror, "Method with cached return value must have non void return type.%n" +
                                "Remove `%s` annotation.", Utilities.getTypeName(typeCache.idempotent));
                cacheData = null;
            } else if (idempotentMirror != null || (enforceIdempotent && !noReturnType)) {
                String cacheFieldName = methodToGenerate.getSimpleName() + "Cache";
                if (usedCacheFields.contains(cacheFieldName)) {
                    int index = 2;
                    while (usedCacheFields.contains(cacheFieldName + index)) {
                        index++;
                    }
                    cacheFieldName = cacheFieldName + index;
                }
                usedCacheFields.add(cacheFieldName);
                TypeMirror type = methodToGenerateType.getReturnType();
                if (type.getKind().isPrimitive()) {
                    type = types.boxedClass((PrimitiveType) type).asType();
                }
                cacheData = new CacheData(cacheFieldName, type);
            } else {
                cacheData = null;
            }
            CharSequence receiverMethod = findReceiverMethod(methodToGenerate, methodToGenerateType, serviceType);
            MethodData methodData = new MethodData(methodToGenerate, methodToGenerateType, overloadIds.get(methodToGenerate),
                            receiverMethod, retMarshaller, paramsMarshallers, cacheData);
            toGenerate.add(methodData);
        }
        return toGenerate;
    }

    private Collection<ExecutableElement> methodsToGenerate(DeclaredType annotatedType, Iterable<? extends ExecutableElement> methods) {
        List<ExecutableElement> res = new ArrayList<>();
        for (ExecutableElement method : methods) {
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.NATIVE) || modifiers.contains(Modifier.FINAL) || modifiers.contains(Modifier.PRIVATE)) {
                continue;
            }
            boolean visible = modifiers.contains(Modifier.PUBLIC);
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            if (!visible && !getPackage(annotatedType.asElement()).equals(getPackage(owner))) {
                continue;
            }
            if (types.isSameType(typeCache.object, owner.asType()) || types.isSameType(myConfiguration.getProxyBaseType(), owner.asType())) {
                continue;
            }
            res.add(method);
        }
        return res;
    }

    private static PackageElement getPackage(Element element) {
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null && enclosing.getKind() != ElementKind.PACKAGE) {
            enclosing = enclosing.getEnclosingElement();
        }
        return (PackageElement) enclosing;
    }

    private List<MarshallerData> lookupMarshallers(ExecutableElement method, List<? extends VariableElement> parameters, List<? extends TypeMirror> parameterTypes,
                    boolean explicitReceiver) {
        List<MarshallerData> res = new ArrayList<>(parameterTypes.size());
        for (int i = 0; i < parameters.size(); i++) {
            res.add(lookupMarshaller(method, parameterTypes.get(i), parameters.get(i).getAnnotationMirrors(), explicitReceiver));
        }
        return res;
    }

    private MarshallerData lookupMarshaller(ExecutableElement method, TypeMirror type, List<? extends AnnotationMirror> annotationMirrors, boolean explicitReceiver) {
        MarshallerData res;
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            res = MarshallerData.NO_MARSHALLER;
        } else if (types.isSameType(typeCache.string, type)) {
            res = MarshallerData.NO_MARSHALLER;
        } else if (isPrimitiveArray(type)) {
            res = MarshallerData.annotatedArray(findDirectionModifiers(annotationMirrors));
        } else {
            AnnotationMirror annotationMirror = findByReference(annotationMirrors);
            if (annotationMirror != null) {
                DeclaredType referenceType = (DeclaredType) getAnnotationValue(annotationMirror, "value");
                TypeElement referenceElement = (TypeElement) referenceType.asElement();
                boolean useReceiverResolver = (boolean) getAnnotationValueWithDefaults(annotationMirror, "useReceiverResolver");
                if (useReceiverResolver && !explicitReceiver) {
                    emitError(method, annotationMirror, "UseReceiverResolver can be used only for types with explicit receiver.");
                }
                boolean sameDirection = true;
                VariableElement nonDefaultReceiver = null;
                ExecutableElement factoryMethod = null;
                ConstructorSelector selector;
                if (types.isSubtype(referenceType, myConfiguration.getProxyBaseType())) {
                    selector = ConstructorSelector.withParameters(AbstractBridgeParser.this, method, annotationMirror, referenceElement, types, myConfiguration.getSubClassReferenceConstructorTypes(),
                                    true);
                } else if (types.isSubtype(referenceType, otherConfiguration.getProxyBaseType())) {
                    selector = ConstructorSelector.withParameters(AbstractBridgeParser.this, method, annotationMirror, referenceElement, types,
                                    otherConfiguration.getSubClassReferenceConstructorTypes(), true);
                    sameDirection = false;
                } else if ((factoryMethod = factoryMethodIn(ElementFilter.methodsIn(referenceElement.getEnclosedElements()), false, null)) != null) {
                    if (processor.getAnnotation(referenceElement, myConfiguration.getHandledAnnotationType()) != null) {
                        referenceType = myConfiguration.getProxyBaseType();
                    } else if (processor.getAnnotation(referenceElement, otherConfiguration.getHandledAnnotationType()) != null) {
                        sameDirection = false;
                        referenceType = otherConfiguration.getProxyBaseType();
                    } else {
                        CharSequence referenceTypeName = Utilities.getTypeName(referenceType);
                        CharSequence myAnnotationName = Utilities.getTypeName(myConfiguration.getHandledAnnotationType());
                        CharSequence otherAnnotationName = Utilities.getTypeName(otherConfiguration.getHandledAnnotationType());
                        emitError(method, annotationMirror, "The `%s` is must be explicit receiver class annotated by `%s` or `%s`.%n" +
                                        "Annotate the `%s` with `%s` or `%s`.", referenceTypeName, myAnnotationName, otherAnnotationName,
                                        referenceElement, myAnnotationName, otherAnnotationName);
                    }
                    referenceElement = (TypeElement) referenceType.asElement();
                    selector = null;
                } else {
                    selector = null;
                    nonDefaultReceiver = ElementFilter.fieldsIn(referenceElement.getEnclosedElements()).stream().filter(
                                    (f) -> processor.getAnnotation(f, typeCache.endPointHandle) != null).findAny().orElse(null);
                    if (nonDefaultReceiver != null) {
                        if (types.isSubtype(nonDefaultReceiver.asType(), myConfiguration.getProxyBaseType())) {
                            selector = ConstructorSelector.withParameters(AbstractBridgeParser.this, method, annotationMirror, referenceElement, types,
                                            myConfiguration.getHandleReferenceConstructorTypes(), true);
                        } else if (types.isSubtype(nonDefaultReceiver.asType(), otherConfiguration.getProxyBaseType())) {
                            selector = ConstructorSelector.withParameters(AbstractBridgeParser.this, method, annotationMirror, referenceElement, types,
                                            otherConfiguration.getHandleReferenceConstructorTypes(), true);
                            sameDirection = false;
                        }
                    }
                    if (selector == null) {
                        emitError(method, annotationMirror, "Cannot lookup `%s` annotated field in `%s`", Utilities.getTypeName(typeCache.endPointHandle), Utilities.getTypeName(referenceType));
                    }
                }
                if (selector != null) {
                    ElementFilter.constructorsIn(referenceElement.getEnclosedElements()).forEach(selector);
                    selector.get();
                }
                AnnotationMirror annotation = processor.getAnnotation(referenceElement,
                                sameDirection ? myConfiguration.getHandledAnnotationType() : otherConfiguration.getHandledAnnotationType());
                res = MarshallerData.reference(referenceType, annotation, useReceiverResolver, sameDirection, nonDefaultReceiver, factoryMethod);
            } else {
                List<? extends AnnotationMirror> annotations = filterMarshallerAnnotations(annotationMirrors, ignoreAnnotations);
                String marshallerFieldName = marshallerName(type, annotations);
                res = MarshallerData.marshalled(type, marshallerFieldName, annotations);
            }
        }
        return res;
    }

    private List<? extends AnnotationMirror> filterMarshallerAnnotations(List<? extends AnnotationMirror> annotationMirrors,
                    Collection<? extends DeclaredType> ignoredAnnotations) {
        List<AnnotationMirror> result = new ArrayList<>();
        for (AnnotationMirror mirror : annotationMirrors) {
            DeclaredType type = mirror.getAnnotationType();
            if (!isIgnoredAnnotation(type, ignoredAnnotations)) {
                result.add(mirror);
            }
        }
        return result;
    }

    private boolean isIgnoredAnnotation(DeclaredType type, Collection<? extends DeclaredType> ignoredAnnotations) {
        for (DeclaredType annotation : ignoredAnnotations) {
            if (types.isSameType(annotation, type)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Modifier> staticNonPrivate(Set<Modifier> modifiers) {
        Set<Modifier> newModifiers = EnumSet.noneOf(Modifier.class);
        // Use dispatch resolver modifiers
        newModifiers.addAll(modifiers);
        // Remove private if present
        newModifiers.remove(Modifier.PRIVATE);
        // Add static if missing
        newModifiers.add(Modifier.STATIC);
        // Remove final if present, it's illegal for static methods
        newModifiers.remove(Modifier.FINAL);
        // Remove abstract if present, it's illegal for static methods
        newModifiers.remove(Modifier.ABSTRACT);
        return newModifiers;
    }

    private static String marshallerName(TypeMirror type, List<? extends AnnotationMirror> annotations) {
        StringBuilder className = new StringBuilder();
        buildMarshallerNameFromType(className, type);
        if (!annotations.isEmpty()) {
            className.append("With");
            for (AnnotationMirror annotation : annotations) {
                className.append(annotation.getAnnotationType().asElement().getSimpleName());
            }
        }
        className.append("Marshaller");
        className.setCharAt(0, Character.toLowerCase(className.charAt(0)));
        return className.toString();
    }

    private static void buildMarshallerNameFromType(StringBuilder builder, TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN:
                builder.append("boolean");
                break;
            case BYTE:
                builder.append("byte");
                break;
            case SHORT:
                builder.append("short");
                break;
            case CHAR:
                builder.append("char");
                break;
            case INT:
                builder.append("int");
                break;
            case LONG:
                builder.append("long");
                break;
            case FLOAT:
                builder.append("float");
                break;
            case DOUBLE:
                builder.append("double");
                break;
            case DECLARED:
                DeclaredType declaredType = (DeclaredType) type;
                builder.append(declaredType.asElement().getSimpleName());
                List<? extends TypeMirror> typeParameters = declaredType.getTypeArguments();
                if (!typeParameters.isEmpty()) {
                    builder.append("Of");
                    for (TypeMirror typeParameter : typeParameters) {
                        buildMarshallerNameFromType(builder, typeParameter);
                    }
                }
                break;
            case ARRAY:
                buildMarshallerNameFromType(builder, ((ArrayType) type).getComponentType());
                builder.append("Array");
                break;
            case WILDCARD:
                builder.append("Object");
                break;
            case TYPEVAR:
                buildMarshallerNameFromType(builder, ((TypeVariable) type).getUpperBound());
                break;
            case INTERSECTION:
                for (Iterator<? extends TypeMirror> it = ((IntersectionType) type).getBounds().iterator(); it.hasNext();) {
                    buildMarshallerNameFromType(builder, it.next());
                    if (it.hasNext()) {
                        builder.append("And");
                    }
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported: " + type.getKind());
        }
    }

    private static boolean isPrimitiveArray(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY && ((ArrayType) type).getComponentType().getKind().isPrimitive();
    }

    private AnnotationMirror findByReference(List<? extends AnnotationMirror> annotationMirrors) {
        for (AnnotationMirror mirror : annotationMirrors) {
            DeclaredType type = mirror.getAnnotationType();
            if (types.isSameType(typeCache.byReference, type)) {
                return mirror;
            }
        }
        return null;
    }

    private List<? extends AnnotationMirror> findDirectionModifiers(List<? extends AnnotationMirror> annotationMirrors) {
        List<AnnotationMirror> res = null;
        for (AnnotationMirror mirror : annotationMirrors) {
            DeclaredType type = mirror.getAnnotationType();
            if (types.isSameType(typeCache.in, type) || types.isSameType(typeCache.out, type)) {
                if (res == null) {
                    res = new ArrayList<>(2);
                }
                res.add(mirror);
            }
        }
        if (res != null && res.size() == 1 && isDefaultIn(res.get(0))) {
            res = null;
        }
        return res != null ? res : Collections.emptyList();
    }

    private boolean isDefaultIn(AnnotationMirror mirror) {
        return types.isSameType(typeCache.in, mirror.getAnnotationType()) &&
                        getAnnotationValue(mirror, "arrayOffsetParameter") == null &&
                        getAnnotationValue(mirror, "arrayLengthParameter") == null;
    }

    private CharSequence findReceiverMethod(ExecutableElement executable, ExecutableType executableType, DeclaredType serviceType) {
        AnnotationMirror receiverMethodMirror = processor.getAnnotation(executable, typeCache.receiverMethod);
        if (receiverMethodMirror == null) {
            return null;
        }
        CharSequence receiverMethodName = (CharSequence) getAnnotationValue(receiverMethodMirror, "value");
        ExecutableElement found = null;
        for (ExecutableElement receiverCandidate : ElementFilter.methodsIn(elements.getAllMembers((TypeElement) serviceType.asElement()))) {
            if (!receiverCandidate.getSimpleName().contentEquals(receiverMethodName)) {
                continue;
            }
            if (!compatibleSignature((ExecutableType) types.asMemberOf(serviceType, receiverCandidate), executableType)) {
                continue;
            }
            found = receiverCandidate;
            break;
        }
        if (found == null) {
            emitError(executable, receiverMethodMirror, "Method `%s%s` is not found in `%s`. " +
                            "Receiver method must have the same arguments as the annotated method and must exist in the bridged type.",
                            receiverMethodName, getSignature(executableType.getParameterTypes()), Utilities.getTypeName(serviceType));
        } else if (found.getModifiers().contains(Modifier.STATIC) || found.getModifiers().contains(Modifier.PRIVATE)) {
            emitError(executable, receiverMethodMirror, "Receiver method `%s` must be a non private instance method.", receiverMethodName);
        }
        return receiverMethodName;
    }

    private boolean compatibleSignature(ExecutableType receiverMethod, ExecutableType implMethod) {
        if (!types.isSubtype(receiverMethod.getReturnType(), implMethod.getReturnType())) {
            return false;
        }
        List<? extends TypeMirror> parameterTypes1 = receiverMethod.getParameterTypes();
        List<? extends TypeMirror> parameterTypes2 = implMethod.getParameterTypes();
        if (parameterTypes1.size() != parameterTypes2.size()) {
            return false;
        }
        for (int i = 0; i < parameterTypes1.size(); i++) {
            if (!types.isSameType(parameterTypes1.get(i), parameterTypes2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void assertNoExpectedErrors(DefinitionData definitionData) {
        ExpectError.assertNoErrorExpected(this, definitionData.annotatedType.asElement());
        if (definitionData.dispatchAccessor != null) {
            ExpectError.assertNoErrorExpected(this, definitionData.dispatchAccessor);
        }
        if (definitionData.receiverAccessor != null) {
            ExpectError.assertNoErrorExpected(this, definitionData.receiverAccessor);
        }
        for (MethodData methodData : definitionData.toGenerate) {
            ExpectError.assertNoErrorExpected(this, methodData.element);
        }
        if (definitionData.endPointHandle != null) {
            ExpectError.assertNoErrorExpected(this, definitionData.endPointHandle);
        }
        if (definitionData.exceptionHandler != null) {
            ExpectError.assertNoErrorExpected(this, definitionData.exceptionHandler);
        }
    }

    private void checkAnnotatedType(Element annotatedElement, AnnotationMirror annotation) {
        if (!annotatedElement.getKind().isClass()) {
            emitError(annotatedElement, annotation, "Annotation is supported only on type declarations.");
        }
        if (annotatedElement.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            emitError(annotatedElement, annotation, "Annotation is supported only on top level types. Make the `%s` a top level class.", annotatedElement.getSimpleName());
        }
    }

    final void emitError(Element element, AnnotationMirror mirror, String format, Object... params) {
        hasErrors = true;
        String msg = String.format(format, params);
        if (ExpectError.isExpectedError(this, element, msg)) {
            return;
        }
        this.processor.env().getMessager().printMessage(Diagnostic.Kind.ERROR, msg, element, mirror);
    }

    private static CharSequence getSignature(List<? extends TypeMirror> types) {
        return getSignature(types, false, false);
    }

    private static CharSequence getSignature(List<? extends TypeMirror> types, boolean anyBefore, boolean anyAfter) {
        String prefix = anyBefore ? "(...," : "(";
        String suffix = anyAfter ? ",...)" : ")";
        return types.stream().map(Utilities::getTypeName).collect(Collectors.joining(", ", prefix, suffix));
    }

    private static Collection<CharSequence> getSignatures(Iterable<List<? extends TypeMirror>> signatures, boolean anyBefore, boolean anyAfter) {
        List<CharSequence> result = new ArrayList<>();
        for (List<? extends TypeMirror> signature : signatures) {
            result.add(getSignature(signature, anyBefore, anyAfter));
        }
        return result;
    }

    private static List<? extends VariableElement> findConstructorParams(DeclaredType type, ConstructorSelector constructorSelector) {
        TypeElement te = (TypeElement) type.asElement();
        ElementFilter.constructorsIn(te.getEnclosedElements()).stream().forEach(constructorSelector::accept);
        ExecutableElement selectedConstructor = constructorSelector.get();
        return selectedConstructor == null ? Collections.emptyList() : selectedConstructor.getParameters();
    }

    private abstract static class ConstructorSelector implements Consumer<ExecutableElement>, Supplier<ExecutableElement> {

        protected final AbstractBridgeParser parser;
        ExecutableElement accepted;

        ConstructorSelector(AbstractBridgeParser parser) {
            this.parser = parser;
        }

        static ConstructorSelector singleConstructor(AbstractBridgeParser parser, TypeElement annotatedElement, AnnotationMirror mirror) {
            return new ConstructorSelector(parser) {

                @Override
                public void accept(ExecutableElement executableElement) {
                    if (accepted == null) {
                        accepted = executableElement;
                    } else {
                        parser.emitError(annotatedElement, mirror, "Annotated type must have a single constructor.");
                    }
                }

                @Override
                public ExecutableElement get() {
                    return accepted;
                }
            };
        }

        static ConstructorSelector withParameters(AbstractBridgeParser parser, Element annotatedElement, AnnotationMirror mirror,
                        TypeElement enclosingElement, Types types, Iterable<List<? extends TypeMirror>> parameterTypes, boolean sameArity) {
            return new ConstructorSelector(parser) {

                @Override
                public void accept(ExecutableElement executableElement) {
                    List<? extends VariableElement> params = executableElement.getParameters();
                    boolean validParams = true;
                    for (List<? extends TypeMirror> signature : parameterTypes) {
                        if (params.size() < signature.size() || (sameArity && params.size() != signature.size())) {
                            validParams = false;
                        } else {
                            validParams = true;
                            for (int i = 0; i < signature.size(); i++) {
                                TypeMirror parameter = params.get(i).asType();
                                TypeMirror requiredParameter = signature.get(i);
                                boolean compatible = requiredParameter.getKind().isPrimitive() ? types.isSameType(parameter, requiredParameter) : types.isAssignable(parameter, requiredParameter);
                                if (!compatible) {
                                    validParams = false;
                                    break;
                                }
                            }
                            if (validParams) {
                                break;
                            }
                        }
                    }
                    if (validParams) {
                        if (accepted == null) {
                            accepted = executableElement;
                        } else {
                            invalidConstructor();
                        }
                    }
                }

                @Override
                public ExecutableElement get() {
                    if (accepted == null) {
                        invalidConstructor();
                    }
                    return accepted;
                }

                void invalidConstructor() {
                    Collection<CharSequence> expectedSignatures = getSignatures(parameterTypes, false, !sameArity);
                    CharSequence name = enclosingElement.getSimpleName();
                    CharSequence constructorToAdd = Utilities.printMethod(Collections.emptySet(), name, types.getNoType(TypeKind.NONE), parameterTypes.iterator().next().toArray(new TypeMirror[0]));
                    if (expectedSignatures.size() == 1) {
                        parser.emitError(annotatedElement, mirror, "The annotated type must have a single constructor with `%s` signature.%n" +
                                        "Add `%s` constructor into `%s`.", expectedSignatures.iterator().next(), constructorToAdd, name);
                    } else {
                        parser.emitError(annotatedElement, mirror, "The annotated type must have a single constructor with one of the following signatures %s.%n" +
                                        "Add `%s` constructor into `%s`.", expectedSignatures.stream().map((s) -> "`" + s + "`").collect(Collectors.joining(", ")),
                                        constructorToAdd, name);
                    }
                }
            };
        }
    }

    static final class MarshallerData {

        enum Kind {
            VALUE,
            REFERENCE,
            CUSTOM
        }

        static final MarshallerData NO_MARSHALLER = new MarshallerData(Kind.VALUE, null, null, false, true, null, null, null);

        final Kind kind;
        final TypeMirror forType;
        final List<? extends AnnotationMirror> annotations;
        final String name;                          // only for CUSTOM
        final boolean useReceiverResolver;          // only for REFERENCE
        final boolean sameDirection;                // only for REFERENCE
        final VariableElement nonDefaultReceiver;   // only for REFERENCE
        final ExecutableElement factoryMethod;      // only for REFERENCE

        private MarshallerData(Kind kind, TypeMirror forType, String name, boolean useReceiverResolver, boolean sameDirection,
                        List<? extends AnnotationMirror> annotations, VariableElement nonDefaultReceiver, ExecutableElement factoryMethod) {
            this.kind = kind;
            this.forType = forType;
            this.name = name;
            this.useReceiverResolver = useReceiverResolver;
            this.sameDirection = sameDirection;
            this.annotations = annotations;
            this.nonDefaultReceiver = nonDefaultReceiver;
            this.factoryMethod = factoryMethod;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MarshallerData that = (MarshallerData) o;
            return Objects.equals(forType, that.forType) && Objects.equals(name, that.name) && useReceiverResolver == that.useReceiverResolver && Objects.equals(annotations, that.annotations);
        }

        @Override
        public int hashCode() {
            return Objects.hash(forType, name);
        }

        static MarshallerData annotatedArray(List<? extends AnnotationMirror> annotations) {
            return annotations == null ? NO_MARSHALLER : new MarshallerData(Kind.VALUE, null, null, false, true, annotations, null, null);
        }

        static MarshallerData marshalled(TypeMirror forType, String name, List<? extends AnnotationMirror> annotations) {
            return new MarshallerData(Kind.CUSTOM, forType, name, false, true, annotations, null, null);
        }

        static MarshallerData reference(DeclaredType startPointType, AnnotationMirror annotation, boolean useReceiverResolver,
                        boolean sameDirection, VariableElement nonDefaultReceiver, ExecutableElement factoryMethod) {
            List<AnnotationMirror> annotations = annotation == null ? Collections.emptyList() : Collections.singletonList(annotation);
            return new MarshallerData(Kind.REFERENCE, startPointType, null, useReceiverResolver, sameDirection, annotations, nonDefaultReceiver, factoryMethod);
        }
    }

    static final class CacheData {

        final String cacheFieldName;
        final TypeMirror cacheEntryType;

        CacheData(String cacheFieldName, TypeMirror cacheEntryType) {
            this.cacheFieldName = cacheFieldName;
            this.cacheEntryType = cacheEntryType;
        }
    }

    static final class MethodData {

        final ExecutableElement element;
        final ExecutableType type;
        final int overloadId;
        final CharSequence receiverMethod;
        final CacheData cachedData;
        private final MarshallerData returnTypeMarshaller;
        private final List<MarshallerData> parameterMarshallers;

        MethodData(ExecutableElement element, ExecutableType type, int overloadId, CharSequence receiverMethod,
                        MarshallerData returnTypeMarshaller,
                        List<MarshallerData> parameterMarshallers,
                        CacheData cacheData) {
            this.element = element;
            this.type = type;
            this.overloadId = overloadId;
            this.receiverMethod = receiverMethod;
            this.cachedData = cacheData;
            this.returnTypeMarshaller = returnTypeMarshaller;
            this.parameterMarshallers = parameterMarshallers;
        }

        MarshallerData getReturnTypeMarshaller() {
            return returnTypeMarshaller;
        }

        MarshallerData getParameterMarshaller(int arg) {
            return parameterMarshallers.get(arg);
        }

        boolean hasOverload() {
            return overloadId > 0;
        }
    }

    static class DefinitionData {

        final DeclaredType annotatedType;
        final List<? extends VariableElement> annotatedTypeConstructorParams;
        final DeclaredType serviceType;
        final Collection<? extends MethodData> toGenerate;
        final ExecutableElement dispatchAccessor;
        final ExecutableElement receiverAccessor;
        final ExecutableElement exceptionHandler;
        final VariableElement endPointHandle;
        final DeclaredType jniConfig;
        final Set<DeclaredType> copyAnnotations;

        DefinitionData(DeclaredType annotatedType, DeclaredType serviceType, Collection<MethodData> toGenerate,
                        List<? extends VariableElement> annotatedTypeConstructorParams, ExecutableElement dispatchAccessor,
                        ExecutableElement receiverAccessor, ExecutableElement exceptionHandler, VariableElement endPointHandle,
                        DeclaredType jniConfig, Set<DeclaredType> copyAnnotations) {
            this.annotatedType = annotatedType;
            this.annotatedTypeConstructorParams = annotatedTypeConstructorParams;
            this.serviceType = serviceType;
            this.toGenerate = toGenerate;
            this.dispatchAccessor = dispatchAccessor;
            this.receiverAccessor = receiverAccessor;
            this.exceptionHandler = exceptionHandler;
            this.endPointHandle = endPointHandle;
            this.jniConfig = jniConfig;
            this.copyAnnotations = copyAnnotations;
        }

        Collection<MarshallerData> getAllCustomMarshallers() {
            SortedSet<MarshallerData> res = new TreeSet<>(Comparator.comparing(a -> a.name));
            collectAllMarshallers(res, MarshallerData.Kind.CUSTOM);
            return res;
        }

        Collection<MarshallerData> getAllReferenceMarshallers() {
            Set<MarshallerData> res = new HashSet<>();
            collectAllMarshallers(res, MarshallerData.Kind.REFERENCE);
            return res;
        }

        private void collectAllMarshallers(Set<? super MarshallerData> into, MarshallerData.Kind kind) {
            for (MethodData methodData : toGenerate) {
                Optional.of(methodData.returnTypeMarshaller).filter((m) -> m.kind == kind).ifPresent(into::add);
                methodData.parameterMarshallers.stream().filter((m) -> m.kind == kind).forEach(into::add);
            }
        }

        boolean hasExplicitReceiver() {
            return dispatchAccessor != null;
        }

        String getTargetClassSimpleName() {
            return annotatedType.asElement().getSimpleName() + "Gen";
        }

    }

    abstract static class AbstractTypeCache {

        final DeclaredType annotationAction;
        final DeclaredType getAnnotationActionRepeated;
        final DeclaredType byReference;
        final DeclaredType clazz;
        final DeclaredType collections;
        final DeclaredType dispatchResolver;
        final DeclaredType endPointHandle;
        final DeclaredType exceptionHandler;
        final DeclaredType expectError;
        final DeclaredType factory;
        final DeclaredType generateHSToNativeBridge;
        final DeclaredType generateNativeToHSBridge;
        final DeclaredType hSObject;
        final DeclaredType idempotent;
        final DeclaredType in;
        final DeclaredType jBooleanArray;
        final DeclaredType jByteArray;
        final DeclaredType jCharArray;
        final DeclaredType jClass;
        final DeclaredType jDoubleArray;
        final DeclaredType jFloatArray;
        final DeclaredType jIntArray;
        final DeclaredType jLongArray;
        final DeclaredType jObject;
        final DeclaredType jShortArray;
        final DeclaredType jString;
        final DeclaredType jThrowable;
        final DeclaredType jniConfig;
        final DeclaredType jniEnv;
        final DeclaredType jniHotSpotMarshaller;
        final DeclaredType jniMethodScope;
        final DeclaredType jniNativeMarshaller;
        final DeclaredType jniUtil;
        final DeclaredType map;
        final DeclaredType nativeIsolate;
        final DeclaredType nativeObject;
        final DeclaredType nativeObjectHandles;
        final DeclaredType object;
        final DeclaredType out;
        final DeclaredType override;
        final DeclaredType receiverMethod;
        final DeclaredType receiverResolver;
        final DeclaredType string;
        final DeclaredType suppressWarnings;
        final DeclaredType throwable;
        final DeclaredType typeLiteral;
        final DeclaredType weakHashMap;
        final DeclaredType wordFactory;

        AbstractTypeCache(NativeBridgeProcessor processor) {
            this.annotationAction = (DeclaredType) processor.getType("org.graalvm.nativebridge.AnnotationAction");
            this.getAnnotationActionRepeated = (DeclaredType) processor.getType("org.graalvm.nativebridge.AnnotationActionRepeated");
            this.byReference = (DeclaredType) processor.getType("org.graalvm.nativebridge.ByReference");
            this.clazz = (DeclaredType) processor.getType("java.lang.Class");
            this.collections = (DeclaredType) processor.getType("java.util.Collections");
            this.dispatchResolver = (DeclaredType) processor.getType("org.graalvm.nativebridge.DispatchResolver");
            this.endPointHandle = (DeclaredType) processor.getType("org.graalvm.nativebridge.EndPointHandle");
            this.exceptionHandler = (DeclaredType) processor.getType("org.graalvm.nativebridge.ExceptionHandler");
            this.expectError = (DeclaredType) processor.getTypeOrNull("org.graalvm.nativebridge.processor.test.ExpectError");
            this.factory = (DeclaredType) processor.getType("org.graalvm.nativebridge.Factory");
            this.generateHSToNativeBridge = (DeclaredType) processor.getType(HotSpotToNativeBridgeParser.GENERATE_HOTSPOT_TO_NATIVE_ANNOTATION);
            this.generateNativeToHSBridge = (DeclaredType) processor.getType(NativeToHotSpotBridgeParser.GENERATE_NATIVE_TO_HOTSPOT_ANNOTATION);
            this.hSObject = (DeclaredType) processor.getType("org.graalvm.jniutils.HSObject");
            this.idempotent = (DeclaredType) processor.getType("org.graalvm.nativebridge.Idempotent");
            this.in = (DeclaredType) processor.getType("org.graalvm.nativebridge.In");
            this.jBooleanArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JBooleanArray");
            this.jByteArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JByteArray");
            this.jCharArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JCharArray");
            this.jClass = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JClass");
            this.jDoubleArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JDoubleArray");
            this.jFloatArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JFloatArray");
            this.jIntArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JIntArray");
            this.jLongArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JLongArray");
            this.jObject = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JObject");
            this.jShortArray = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JShortArray");
            this.jString = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JString");
            this.jThrowable = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JThrowable");
            this.jniConfig = (DeclaredType) processor.getType("org.graalvm.nativebridge.JNIConfig");
            this.jniEnv = (DeclaredType) processor.getType("org.graalvm.jniutils.JNI.JNIEnv");
            this.jniHotSpotMarshaller = (DeclaredType) processor.getType("org.graalvm.nativebridge.JNIHotSpotMarshaller");
            this.jniMethodScope = (DeclaredType) processor.getType("org.graalvm.jniutils.JNIMethodScope");
            this.jniNativeMarshaller = (DeclaredType) processor.getType("org.graalvm.nativebridge.JNINativeMarshaller");
            this.jniUtil = (DeclaredType) processor.getType("org.graalvm.jniutils.JNIUtil");
            this.map = (DeclaredType) processor.getType("java.util.Map");
            this.nativeIsolate = (DeclaredType) processor.getType("org.graalvm.nativebridge.NativeIsolate");
            this.nativeObject = (DeclaredType) processor.getType("org.graalvm.nativebridge.NativeObject");
            this.nativeObjectHandles = (DeclaredType) processor.getType("org.graalvm.nativebridge.NativeObjectHandles");
            this.object = (DeclaredType) processor.getType("java.lang.Object");
            this.out = (DeclaredType) processor.getType("org.graalvm.nativebridge.Out");
            this.override = (DeclaredType) processor.getType("java.lang.Override");
            this.receiverMethod = (DeclaredType) processor.getType("org.graalvm.nativebridge.ReceiverMethod");
            this.receiverResolver = (DeclaredType) processor.getType("org.graalvm.nativebridge.ReceiverResolver");
            this.string = (DeclaredType) processor.getType("java.lang.String");
            this.suppressWarnings = (DeclaredType) processor.getType("java.lang.SuppressWarnings");
            this.throwable = (DeclaredType) processor.getType("java.lang.Throwable");
            this.typeLiteral = (DeclaredType) processor.getType("org.graalvm.polyglot.TypeLiteral");
            this.weakHashMap = (DeclaredType) processor.getType("java.util.WeakHashMap");
            this.wordFactory = (DeclaredType) processor.getType("org.graalvm.word.WordFactory");
        }
    }

    static final class Configuration {

        private final DeclaredType handledAnnotationType;
        private final DeclaredType proxyBaseType;
        private final Iterable<List<? extends TypeMirror>> subClassReferenceConstructorTypes;
        private final Iterable<List<? extends TypeMirror>> handleReferenceConstructorTypes;

        Configuration(DeclaredType handledAnnotationType, DeclaredType proxyBaseType,
                        Iterable<List<? extends TypeMirror>> subClassReferenceConstructorTypes,
                        Iterable<List<? extends TypeMirror>> handleReferenceConstructorTypes) {
            this.handledAnnotationType = Objects.requireNonNull(handledAnnotationType, "HandledAnnotationType must be non null.");
            this.proxyBaseType = Objects.requireNonNull(proxyBaseType, "ProxyBaseType must be non null.");
            this.subClassReferenceConstructorTypes = Objects.requireNonNull(subClassReferenceConstructorTypes, "SubClassReferenceConstructorTypes must be non null.");
            this.handleReferenceConstructorTypes = Objects.requireNonNull(handleReferenceConstructorTypes, "HandleReferenceConstructorTypes must be non null.");
        }

        DeclaredType getHandledAnnotationType() {
            return handledAnnotationType;
        }

        DeclaredType getProxyBaseType() {
            return proxyBaseType;
        }

        Iterable<List<? extends TypeMirror>> getSubClassReferenceConstructorTypes() {
            return subClassReferenceConstructorTypes;
        }

        Iterable<List<? extends TypeMirror>> getHandleReferenceConstructorTypes() {
            return handleReferenceConstructorTypes;
        }
    }

    private static final class AnnotationAction {
        /**
         * AnnotationMirror defining this AnnotationAction.
         */
        final AnnotationMirror source;

        /**
         * Annotation to which this action is applied.
         */
        final DeclaredType annotation;

        /**
         * Action defined by {@code org.graalvm.nativebridge.AnnotationAction.Action}.
         */
        final String action;

        private AnnotationAction(AnnotationMirror source, DeclaredType annotation, String action) {
            this.source = source;
            this.annotation = annotation;
            this.action = action;
        }

        static AnnotationAction create(AnnotationMirror annotation) {
            DeclaredType value = (DeclaredType) getAnnotationValue(annotation, "value");
            VariableElement action = (VariableElement) getAnnotationValue(annotation, "action");
            return new AnnotationAction(annotation, value, action.getSimpleName().toString());
        }
    }
}
