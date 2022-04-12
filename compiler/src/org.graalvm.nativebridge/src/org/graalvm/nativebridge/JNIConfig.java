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
package org.graalvm.nativebridge;

import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.TypeLiteral;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * A configuration used by the {@link NativeIsolate} and classes generated by the native bridge
 * processor.
 *
 * @see GenerateHotSpotToNativeBridge
 * @see GenerateHotSpotToNativeBridge
 */
public final class JNIConfig {

    private final Map<Type, BinaryMarshaller<?>> binaryMarshallers;
    private final Map<Class<? extends Annotation>, List<Pair<Class<?>, BinaryMarshaller<?>>>> annotationBinaryMarshallers;
    private final LongUnaryOperator attachThreadAction;
    private final LongUnaryOperator detachThreadAction;
    private final LongBinaryOperator shutDownIsolateAction;
    private final LongBinaryOperator releaseNativeObjectAction;

    JNIConfig(Map<Type, BinaryMarshaller<?>> binaryMarshallers,
                    Map<Class<? extends Annotation>, List<Pair<Class<?>, BinaryMarshaller<?>>>> annotationBinaryMarshallers,
                    LongUnaryOperator attachThreadAction, LongUnaryOperator detachThreadAction,
                    LongBinaryOperator shutDownIsolateAction, LongBinaryOperator releaseNativeObjectAction) {
        this.binaryMarshallers = binaryMarshallers;
        this.annotationBinaryMarshallers = annotationBinaryMarshallers;
        this.attachThreadAction = attachThreadAction;
        this.detachThreadAction = detachThreadAction;
        this.shutDownIsolateAction = shutDownIsolateAction;
        this.releaseNativeObjectAction = releaseNativeObjectAction;
    }

    /**
     * Looks up {@link BinaryMarshaller} for the {@code type} and {@code annotationTypes}. The
     * method first tries to find a marshaller registered for the {@code type} and some annotation
     * from {@code annotationTypes}. If no such marshaller exists, it tries to find a marshaller
     * registered just for the {@code type}. If there is no such a marshaller it throws the
     * {@link UnsupportedOperationException}.
     *
     * @param type the parameter or return type.
     * @param annotationTypes parameter or method annotation types.
     * @throws UnsupportedOperationException if there is no registered marshaller for the
     *             {@code type}.
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> BinaryMarshaller<T> lookupMarshaller(Class<T> type, Class<? extends Annotation>... annotationTypes) {
        BinaryMarshaller<?> res = lookupBinaryMarshallerImpl(type, annotationTypes);
        if (res != null) {
            return (BinaryMarshaller<T>) res;
        } else {
            throw unsupported(type);
        }
    }

    /**
     * Looks up {@link BinaryMarshaller} for the {@code parameterizedType} and
     * {@code annotationTypes}. The method first tries to find a marshaller registered for the
     * {@code parameterizedType} and some annotation from {@code annotationTypes}. If no such
     * marshaller exists, it tries to find a marshaller registered just for the
     * {@code parameterizedType}. If there is no such a marshaller it throws the
     * {@link UnsupportedOperationException}.
     *
     * @param parameterizedType the parameter or return type.
     * @param annotationTypes parameter or method annotation types.
     * @throws UnsupportedOperationException if there is no registered marshaller for the
     *             {@code parameterizedType}.
     */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public final <T> BinaryMarshaller<T> lookupMarshaller(TypeLiteral<T> parameterizedType, Class<? extends Annotation>... annotationTypes) {
        BinaryMarshaller<?> res = lookupBinaryMarshallerImpl(parameterizedType.getType(), annotationTypes);
        if (res != null) {
            return (BinaryMarshaller<T>) res;
        } else {
            throw unsupported(parameterizedType.getType());
        }
    }

    long attachThread(long isolate) {
        return attachThreadAction.applyAsLong(isolate);
    }

    boolean detachThread(long isolateThread) {
        return detachThreadAction.applyAsLong(isolateThread) == 0;
    }

    boolean releaseNativeObject(long isolateThread, long handle) {
        return releaseNativeObjectAction.applyAsLong(isolateThread, handle) == 0;
    }

    boolean shutDownIsolate(long isolate, long isolateThread) {
        return shutDownIsolateAction.applyAsLong(isolate, isolateThread) == 0;
    }

    private static RuntimeException unsupported(Type type) {
        throw new UnsupportedOperationException(String.format("Marshalling of %s is not supported", type));
    }

    @SafeVarargs
    private final BinaryMarshaller<?> lookupBinaryMarshallerImpl(Type type, Class<? extends Annotation>... annotationTypes) {
        for (Class<? extends Annotation> annotationType : annotationTypes) {
            verifyAnnotation(annotationType);
            BinaryMarshaller<?> res = lookup(annotationBinaryMarshallers, type, annotationType);
            if (res != null) {
                return res;
            }
        }
        return binaryMarshallers.get(type);
    }

    private static <T> T lookup(Map<Class<? extends Annotation>, List<Pair<Class<?>, T>>> marshallers, Type type, Class<? extends Annotation> annotationType) {
        List<Pair<Class<?>, T>> marshallersForAnnotation = marshallers.get(annotationType);
        if (marshallersForAnnotation != null) {
            Class<?> rawType = erasure(type);
            for (Pair<Class<?>, T> marshaller : marshallersForAnnotation) {
                if (marshaller.getLeft().isAssignableFrom(rawType)) {
                    return marshaller.getRight();
                }
            }
        }
        return null;
    }

    private static Class<?> erasure(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        } else if (type instanceof GenericArrayType) {
            return arrayTypeFromComponentType(erasure(((GenericArrayType) type).getGenericComponentType()));
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private static Class<?> arrayTypeFromComponentType(Class<?> componentType) {
        return Array.newInstance(componentType, 0).getClass();
    }

    private static void verifyAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType.getAnnotation(MarshallerAnnotation.class) == null) {
            throw new IllegalArgumentException(String.format("The %s in not a valid marshaller annotation. The marshaller annotation must be annotated by the %s meta-annotation.",
                            annotationType.getSimpleName(), MarshallerAnnotation.class.getSimpleName()));
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * A builder class to construct {@link JNIConfig} instances.
     */
    public static final class Builder {

        private static final LongUnaryOperator ATTACH_UNSUPPORTED = (isolate) -> {
            throw new UnsupportedOperationException("Attach is not supported.");
        };
        private static final LongUnaryOperator DETACH_UNSUPPORTED = (isolateThread) -> {
            throw new UnsupportedOperationException("Detach is not supported.");
        };
        private static final LongBinaryOperator SHUTDOWN_UNSUPPORTED = (isolate, isolateThread) -> {
            throw new UnsupportedOperationException("Isolate shutdown is not supported.");
        };
        private static final LongBinaryOperator RELEASE_UNSUPPORTED = (isolateThread, handle) -> {
            throw new UnsupportedOperationException("Native object clean up is not supported.");
        };

        private final Map<Type, BinaryMarshaller<?>> binaryMarshallers;
        private final Map<Class<? extends Annotation>, List<Pair<Class<?>, BinaryMarshaller<?>>>> annotationBinaryMarshallers;
        private LongUnaryOperator attachThreadAction = ATTACH_UNSUPPORTED;
        private LongUnaryOperator detachThreadAction = DETACH_UNSUPPORTED;
        private LongBinaryOperator shutDownIsolateAction = SHUTDOWN_UNSUPPORTED;
        private LongBinaryOperator releaseNativeObjectAction = RELEASE_UNSUPPORTED;

        Builder() {
            this.binaryMarshallers = new HashMap<>();
            this.annotationBinaryMarshallers = new HashMap<>();
            // Register default marshallers
            this.binaryMarshallers.put(Throwable.class, new DefaultThrowableMarshaller());
            this.binaryMarshallers.put(StackTraceElement[].class, defaultStackTraceMarshaller());
        }

        /**
         * Registers a {@link BinaryMarshaller} for the {@code type}.
         *
         * @param type the type to register {@link BinaryMarshaller} for.
         * @param marshaller the marshaller to register.
         */
        public <T> Builder registerMarshaller(Class<T> type, BinaryMarshaller<T> marshaller) {
            Objects.requireNonNull(type, "Type must be non null.");
            Objects.requireNonNull(marshaller, "Marshaller must be non null.");
            this.binaryMarshallers.put(type, marshaller);
            return this;
        }

        /**
         * Registers a {@link BinaryMarshaller} for the {@code parameterizedType}.
         *
         * @param parameterizedType the type to register {@link BinaryMarshaller} for.
         * @param marshaller the marshaller to register.
         */
        public <T> Builder registerMarshaller(TypeLiteral<T> parameterizedType, BinaryMarshaller<T> marshaller) {
            Objects.requireNonNull(parameterizedType, "ParameterizedType must be non null.");
            Objects.requireNonNull(marshaller, "Marshaller must be non null.");
            this.binaryMarshallers.put(parameterizedType.getType(), marshaller);
            return this;
        }

        /**
         * Registers a {@link BinaryMarshaller} for the {@code type} and {@code annotationType}.
         *
         * @param type the type to register {@link BinaryMarshaller} for.
         * @param annotationType a required annotation to look up the marshaller.
         * @param marshaller the marshaller to register.
         *
         */
        public <T> Builder registerMarshaller(Class<T> type, Class<? extends Annotation> annotationType, BinaryMarshaller<T> marshaller) {
            Objects.requireNonNull(type, "Type must be non null.");
            Objects.requireNonNull(annotationType, "AnnotationType must be non null.");
            Objects.requireNonNull(marshaller, "Marshaller must be non null.");
            insert(annotationBinaryMarshallers, type, annotationType, marshaller);
            return this;
        }

        private static <T> void insert(Map<Class<? extends Annotation>, List<Pair<Class<?>, T>>> into, Class<?> type, Class<? extends Annotation> annotationType, T marshaller) {
            verifyAnnotation(annotationType);
            List<Pair<Class<?>, T>> types = into.computeIfAbsent(annotationType, (k) -> new LinkedList<>());
            Pair<Class<?>, T> toInsert = Pair.create(type, marshaller);
            boolean inserted = false;
            for (ListIterator<Pair<Class<?>, T>> it = types.listIterator(); it.hasNext();) {
                Pair<Class<?>, T> current = it.next();
                if (current.getLeft().isAssignableFrom(type)) {
                    it.set(toInsert);
                    it.add(current);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                types.add(toInsert);
            }
        }

        /**
         * Registers a callback used by the {@link NativeIsolate} to attach the current thread to an
         * isolate.
         *
         * @param action a {@link LongUnaryOperator} that takes an isolate address as a parameter
         *            and returns the isolate thread address.
         */
        public Builder setAttachThreadAction(LongUnaryOperator action) {
            Objects.requireNonNull(action, "Action must be non null.");
            if (!ImageInfo.inImageCode()) {
                this.attachThreadAction = action;
            }

            return this;
        }

        /**
         * Registers a callback used by the {@link NativeIsolate} to detach the current thread from
         * an isolate.
         *
         * @param action a {@link LongUnaryOperator} that takes an isolate thread address as a
         *            parameter and returns {@code 0} on success or non-zero in case of an error.
         */
        public Builder setDetachThreadAction(LongUnaryOperator action) {
            Objects.requireNonNull(action, "Action must be non null.");
            if (!ImageInfo.inImageCode()) {
                this.detachThreadAction = action;
            }
            return this;
        }

        /**
         * Registers a callback used by the {@link NativeIsolate} to tear down the isolate.
         *
         * @param action a {@link LongBinaryOperator} that takes an isolate address and an isolate
         *            thread address as parameters and returns {@code 0} on success or non-zero in
         *            case of an error.
         */
        public Builder setShutDownIsolateAction(LongBinaryOperator action) {
            Objects.requireNonNull(action, "Action must be non null.");
            if (!ImageInfo.inImageCode()) {
                this.shutDownIsolateAction = action;
            }
            return this;
        }

        /**
         * Registers a callback used by the {@link NativeIsolate} to tear down the isolate.
         *
         * @param action a {@link LongUnaryOperator} that takes an isolate thread address as a
         *            parameter and returns {@code 0} on success or non-zero in case of an error.
         */
        public Builder setShutDownIsolateAction(LongUnaryOperator action) {
            Objects.requireNonNull(action, "Action must be non null.");
            return setShutDownIsolateAction((isolateId, isolateThreadId) -> action.applyAsLong(isolateThreadId));
        }

        /**
         * Registers a callback used by the {@link NativeObject} to free an object in a native image
         * heap referenced by the garbage-collected handle. At some point after a
         * {@link NativeObject} is garbage collected, a call to the {@code action} is made to
         * release the corresponding object in the native image heap.
         *
         * @param action a {@link LongBinaryOperator} that takes an isolate thread address and
         *            object handle as parameters and returns {@code 0} on success or non-zero in
         *            case of an error.
         *
         * @see NativeObject
         */
        public Builder setReleaseNativeObjectAction(LongBinaryOperator action) {
            Objects.requireNonNull(action, "Action must be non null.");
            if (!ImageInfo.inImageCode()) {
                this.releaseNativeObjectAction = action;
            }
            return this;
        }

        /**
         * Builds the {@link JNIConfig}.
         */
        public JNIConfig build() {
            return new JNIConfig(binaryMarshallers, annotationBinaryMarshallers,
                            attachThreadAction, detachThreadAction, shutDownIsolateAction,
                            releaseNativeObjectAction);
        }

        /**
         * Returns a {@link BinaryMarshaller} for stack trace marshalling.
         */
        public static BinaryMarshaller<StackTraceElement[]> defaultStackTraceMarshaller() {
            return DefaultStackTraceMarshaller.INSTANCE;
        }
    }
}
