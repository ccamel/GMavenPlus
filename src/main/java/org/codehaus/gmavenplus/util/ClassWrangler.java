/*
 * Copyright (C) 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.gmavenplus.util;

import org.apache.maven.plugin.logging.Log;
import org.codehaus.gmavenplus.model.Version;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;


/**
 * Handles getting Groovy classes and version from the specified classpath.
 * Inspired heavily by Spring's <a href="https://github.com/SpringSource/spring-framework/blob/master/spring-core/src/main/java/org/springframework/util/ReflectionUtils.java">ReflectionUtils</a>.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Rod Johnson
 * @author Costin Leau
 * @author Sam Brannen
 * @author Chris Beams
 * @author Keegan Witt
 */
public class ClassWrangler {

    /**
     * Size for all caches.
     */
    protected static final int CACHE_SIZE = 256;

    /**
     * A cache of previously looked-up classes, for faster reuse.
     */
    protected transient Map<String, Class> classCache = null;

    /**
     * Cached Groovy version.
     */
    protected transient String groovyVersion = null;

    /**
     * Cached whether Groovy supports invokedynamic (indy jar).
     */
    protected transient Boolean isIndy = null;

    /**
     * ClassLoader to use for class wrangling.
     */
    protected ClassLoader classLoader;

    /**
     * Plugin log.
     */
    protected Log log;

    /**
     * Cache for {@link Class#getConstructors()}, allowing for fast iteration.
     */
    protected Map<Class<?>, Constructor[]> constructorsCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, Constructor[]>(CACHE_SIZE));

    /**
     * Cache for {@link Class#getDeclaredConstructors()}, allowing for fast iteration.
     */
    protected Map<Class<?>, Constructor[]> declaredConstructorsCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, Constructor[]>(CACHE_SIZE));

    /**
     * Cache for {@link Class#getDeclaredFields()}, allowing for fast iteration.
     */
    protected Map<Class<?>, Field[]> declaredFieldsCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, Field[]>(CACHE_SIZE));

    /**
     * Cache for {@link Class#getDeclaredMethods()}, allowing for fast iteration.
     */
    protected Map<Class<?>, Method[]> declaredMethodsCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, Method[]>(CACHE_SIZE));

    /**
     * Cache for {@link Class#getMethods()}, allowing for fast iteration.
     */
    protected Map<Class<?>, Method[]> methodsCache = Collections.synchronizedMap(new WeakHashMap<Class<?>, Method[]>(CACHE_SIZE));

    /**
     * Whether initialized (Plexus instantiates the ClassWrangler, but the ClassLoader isn't created until Mojo execution).
     */
    protected boolean initialized = false;

    /**
     * Constructor for use by Plexus
     */
    public ClassWrangler() { }

    /**
     * Returns the classloader used for loading classes.
     *
     * @return the classloader used for loading classes
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Initializes using the specified ClassLoader.
     *
     * @param classLoaderForLoading the ClassLoader to use to load classes
     * @param pluginLog the Maven log to use for logging
     */
    public void initialize(final ClassLoader classLoaderForLoading, final Log pluginLog) {
        log = pluginLog;
        if (!initialized) {
            classLoader = classLoaderForLoading;
            initialized = true;
        }
        Thread.currentThread().setContextClassLoader(classLoader);
    }

    /**
     * Initializes using a new ClassLoader, loaded with the items from the specified classpath.
     *
     * @param classpath the classpath to load the new ClassLoader with
     * @param pluginLog the Maven log to use for logging
     */
    public void initialize(final List classpath, final Log pluginLog) {
        try {
            log = pluginLog;
            if (!initialized) {
                // create an isolated ClassLoader with all the appropriate project dependencies in it
                classLoader = createNewClassLoader(classpath);
                initialized = true;
            } else {
                /*
                 * this is needed for additions to classpath (for example, to add the Java classes from the compiler
                 * plugin, between when the classloader is created during stub generation, and Groovy class compilation.
                 */
                for (Object url : classpath) {
                    invokeMethod(findMethod(URLClassLoader.class, "addURL", URL.class), classLoader, new File((String) url).toURI().toURL());
                }
            }
            Thread.currentThread().setContextClassLoader(classLoader);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the version string of Groovy used from classpath.
     *
     * @return The version string of Groovy used by the project
     */
    public String getGroovyVersionString() {
        if (groovyVersion == null) {
            // this method should work for all Groovy versions >= 1.6.6
            try {
                Class<?> groovySystemClass = getClass("groovy.lang.GroovySystem");
                String ver = (String) invokeStaticMethod(findMethod(groovySystemClass, "getVersion"));
                if (ver != null && ver.length() > 0) {
                    groovyVersion = ver;
                }
            } catch (ClassNotFoundException e) {
                // do nothing, will try another way
            } catch (IllegalAccessException e) {
                // do nothing, will try another way
            } catch (InvocationTargetException e) {
                // do nothing, will try another way
            } catch (IllegalArgumentException e) {
                // do nothing, will try another way
            }

            // this should work for Groovy versions < 1.6.6 (technically can work up to 1.9.0)
            if (groovyVersion == null) {
                log.info("Unable to get Groovy version from GroovySystem, trying InvokerHelper.");
                try {
                    Class<?> invokerHelperClass = getClass("org.codehaus.groovy.runtime.InvokerHelper");
                    String ver = (String) invokeStaticMethod(findMethod(invokerHelperClass, "getVersion"));
                    if (ver != null && ver.length() > 0) {
                        groovyVersion = ver;
                    }
                } catch (ClassNotFoundException e) {
                    // do nothing, will try another way
                } catch (IllegalAccessException e) {
                    // do nothing, will try another way
                } catch (InvocationTargetException e) {
                    // do nothing, will try another way
                } catch (IllegalArgumentException e) {
                    // do nothing, will try another way
                }
            }

            /*
             * This handles the circumstances in which neither the GroovySystem or InvokerHelper methods
             * worked (GAE with versions older than 1.6.6 is one example, see
             * https://jira.codehaus.org/browse/GROOVY-3884).  One case this can't handle properly is uber
             * jars that include Groovy.  It should also be noted this method assumes jars will be named
             * in the Maven convention (<artifactId>-<version>-<classifier>.jar).
             */
            if (groovyVersion == null) {
                log.warn("Unable to get Groovy version from InvokerHelper or GroovySystem, trying jar name.");
                String jar = getGroovyJar();
                int idx = Integer.MAX_VALUE;
                for (int i = 0; i < 9; i++) {
                    int newIdx = jar.indexOf("-" + i);
                    if (newIdx >= 0 && newIdx < idx) {
                        idx = newIdx;
                    }
                }
                if (idx < Integer.MAX_VALUE) {
                    groovyVersion = jar.substring(idx + 1, jar.length() - 4).replace("-indy", "").replace("-grooid", "");
                }
            }
        }

        return groovyVersion;
    }

    /**
     * Gets the version of Groovy used from the classpath.
     *
     * @return The version of Groovy used by the project
     */
    public Version getGroovyVersion() {
        try {
            return Version.parseFromString(getGroovyVersionString());
        } catch (Exception e) {
            log.error("Unable to determine Groovy version.  Is Groovy declared as a dependency?");
            return null;
        }
    }

    /**
     * Gets whether the version of Groovy on the classpath supports invokedynamic.
     *
     * @return <code>true</code> if the version of Groovy uses invokedynamic,
     *         <code>false</code> if not or Groovy dependency cannot be found.
     */
    public boolean isGroovyIndy() {
        if (isIndy == null) {
            try {
                getClass("org.codehaus.groovy.vmplugin.v7.IndyInterface");
                isIndy = true;
            } catch (ClassNotFoundException e) {
                isIndy = false;
            }
        }

        return isIndy;
    }

    /**
     * Returns the filename of the Groovy jar on the classpath.
     *
     * @return the Groovy jar filename
     */
    public String getGroovyJar() {
        try {
            String groovyObjectClassPath = getJarPath();
            String groovyJar = null;
            if (groovyObjectClassPath != null) {
                groovyJar = groovyObjectClassPath.replaceAll("!.+", "");
                groovyJar = groovyJar.substring(groovyJar.lastIndexOf("/") + 1, groovyJar.length());
            }

            return groovyJar;
        } catch (ClassNotFoundException e) {
            log.error("Unable to determine Groovy version.  Is Groovy declared as a dependency?");
            return null;
        }
    }

    /**
     * Returns the path of the Groovy jar on the classpath.
     *
     * @return the path of the Groovy jar
     * @throws ClassNotFoundException when Groovu couldn't be found on the classpath
     */
    protected String getJarPath() throws ClassNotFoundException {
        Class<?> groovyObjectClass = getClass("groovy.lang.GroovyObject");
        String groovyObjectClassPath = String.valueOf(groovyObjectClass.getResource("/" + groovyObjectClass.getName().replace('.', '/') + ".class"));
        if (groovyObjectClassPath == null) {
            CodeSource codeSource = groovyObjectClass.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                groovyObjectClassPath = String.valueOf(codeSource.getLocation());
            }
        }
        return groovyObjectClassPath;
    }

    /**
     * Logs the version of groovy used by this mojo.
     *
     * @param goal The goal to mention in the log statement showing Groovy version
     */
    public void logGroovyVersion(final String goal) {
        if (log.isInfoEnabled()) {
            log.info("Using Groovy " + getGroovyVersionString() + " to perform " + goal + ".");
        }
    }

    /**
     * Creates a new ClassLoader with the specified classpath.
     *
     * @param classpath the classpath (a list of file path Strings) to include in the new loader
     * @return the new ClassLoader
     * @throws MalformedURLException when a classpath element provides a malformed URL
     */
    public ClassLoader createNewClassLoader(final List classpath) throws MalformedURLException {
        List<URL> urlsList = new ArrayList<URL>();
        for (Object classPathObject : classpath) {
            String path = (String) classPathObject;
            urlsList.add(new File(path).toURI().toURL());
        }
        URL[] urlsArray = urlsList.toArray(new URL[urlsList.size()]);
        return new URLClassLoader(urlsArray, ClassLoader.getSystemClassLoader());
    }

    /**
     * Gets a class for the given class name.
     *
     * @param className the class name to retrieve the class for
     * @return the class for the given class name
     * @throws ClassNotFoundException when a class for the specified class name cannot be found
     */
    public Class<?> getClass(final String className) throws ClassNotFoundException {
        if (classCache == null) {
            classCache = Collections.synchronizedMap(new WeakHashMap<String, Class>(CACHE_SIZE));
        }
        Class<?> clazz = classCache.get(className);
        if (clazz == null) {
            clazz = Class.forName(className, true, classLoader);
            classCache.put(className, clazz);
        }
        return clazz;
    }

    protected List<Method> findConcreteMethodsOnInterfaces(final Class<?> clazz) {
        List<Method> result = null;
        for (Class<?> ifc : clazz.getInterfaces()) {
            for (Method ifcMethod : getMethods(ifc)) {
                if (!Modifier.isAbstract(ifcMethod.getModifiers())) {
                    if (result == null) {
                        result = new LinkedList<Method>();
                    }
                    result.add(ifcMethod);
                }
            }
        }
        return result;
    }

    /**
     * Attempt to find a {@link Constructor} on the supplied class with the
     * supplied parameter types. Searches all superclasses up to
     * <code>Object</code>.
     *
     * @param clazz The class to introspect
     * @param paramTypes The parameter types of the method (may be <code>null</code> to indicate any signature)
     * @return The Constructor object
     */
    public Constructor findConstructor(final Class<?> clazz, final Class<?>... paramTypes) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must not be null.");
        }
        Class<?> searchType = clazz;
        while (searchType != null) {
            Constructor[] constructors = searchType.isInterface() ? getConstructors(searchType) : getDeclaredConstructors(searchType);
            for (Constructor constructor : constructors) {
                if (paramTypes == null || Arrays.equals(paramTypes, constructor.getParameterTypes())) {
                    return constructor;
                }
            }
            searchType = searchType.getSuperclass();
        }
        throw new IllegalArgumentException("Unable to find constructor " + clazz.getName() + "(" + Arrays.toString(paramTypes).replaceAll("^\\[", "").replaceAll("\\]$", "").replaceAll("class ", "") + ").");
    }

    /**
     * Attempt to find a {@link Field field} on the supplied {@link Class} with
     * the supplied <code>name</code> and/or {@link Class type}. Searches all
     * superclasses up to {@link Object}.
     *
     * @param clazz The class to introspect
     * @param name The name of the field (may be <code>null</code> if type is specified)
     * @param type The type of the field (may be <code>null</code> if name is specified)
     * @return The corresponding Field object
     */
    public Field findField(final Class<?> clazz, final String name, final Class<?> type) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must not be null");
        }
        if (name == null && type == null) {
            throw new IllegalArgumentException("Either name or type of the field must be specified.");
        }
        Class<?> searchType = clazz;
        while (Object.class != searchType && searchType != null) {
            Field[] fields = getDeclaredFields(searchType);
            for (Field field : fields) {
                if ((name == null || name.equals(field.getName())) && (type == null || type.equals(field.getType()))) {
                    return field;
                }
            }
            searchType = searchType.getSuperclass();
        }
        throw new IllegalArgumentException("Unable to find " + (type != null ? type.getName() : "") + " " + (name != null ? name : "") + ".");
    }

    /**
     * Attempt to find a {@link Method} on the supplied class with the supplied
     * name and parameter types. Searches all superclasses up to
     * <code>Object</code>.
     *
     * @param clazz      The class to introspect
     * @param name       The name of the method
     * @param paramTypes The parameter types of the method
     *                   (may be <code>null</code> to indicate any signature)
     * @return The Method object
     */
    public Method findMethod(final Class<?> clazz, final String name, final Class<?>... paramTypes) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must not be null.");
        }
        if (name == null) {
            throw new IllegalArgumentException("Method name must not be null.");
        }
        Class<?> searchType = clazz;
        while (searchType != null) {
            Method[] methods = searchType.isInterface() ? getMethods(searchType) : getDeclaredMethods(searchType);
            for (Method method : methods) {
                if (name.equals(method.getName()) && (paramTypes == null || Arrays.equals(paramTypes, method.getParameterTypes()))) {
                    return method;
                }
            }
            searchType = searchType.getSuperclass();
        }
        throw new IllegalArgumentException("Unable to find method " + clazz.getName() + "." + name + "(" + Arrays.toString(paramTypes).replaceAll("^\\[", "").replaceAll("\\]$", "").replaceAll("class ", "") + ").");
    }

    /**
     * This variant retrieves {@link Class#getConstructors()} from a local cache
     * in order to avoid the JVM's SecurityManager check and defensive array copying.
     *
     * @param clazz the class to introspect
     * @return the cached array of constructors
     * @see Class#getConstructors()
     */
    protected Constructor[] getConstructors(final Class<?> clazz) {
        Constructor[] result = constructorsCache.get(clazz);
        if (result == null) {
            result  = clazz.getConstructors();
            constructorsCache.put(clazz, result);
        }
        return result;
    }

    /**
     * This variant retrieves {@link Class#getDeclaredConstructors()} from a local cache
     * in order to avoid the JVM's SecurityManager check and defensive array copying.
     *
     * @param clazz the class to introspect
     * @return the cached array of constructors
     * @see Class#getDeclaredConstructors()
     */
    protected Constructor[] getDeclaredConstructors(final Class<?> clazz) {
        Constructor[] result = declaredConstructorsCache.get(clazz);
        if (result == null) {
            result  = clazz.getDeclaredConstructors();
            declaredConstructorsCache.put(clazz, result);
        }
        return result;
    }

    /**
     * This variant retrieves {@link Class#getDeclaredFields()} from a local cache
     * in order to avoid the JVM's SecurityManager check and defensive array copying.
     *
     * @param clazz the class to introspect
     * @return the cached array of fields
     * @see Class#getDeclaredFields()
     */
    protected Field[] getDeclaredFields(final Class<?> clazz) {
        Field[] result = declaredFieldsCache.get(clazz);
        if (result == null) {
            result = clazz.getDeclaredFields();
            declaredFieldsCache.put(clazz, result);
        }
        return result;
    }

    /**
     * This variant retrieves {@link Class#getDeclaredMethods()} from a local cache
     * in order to avoid the JVM's SecurityManager check and defensive array copying.
     * In addition, it also includes Java 8 default methods from locally implemented
     * interfaces, since those are effectively to be treated just like declared methods.
     *
     *  @param clazz the class to introspect
     * @return the cached array of methods
     * @see Class#getDeclaredMethods()
     */
    protected Method[] getDeclaredMethods(final Class<?> clazz) {
        Method[] result = declaredMethodsCache.get(clazz);
        if (result == null) {
            Method[] declaredMethods = clazz.getDeclaredMethods();
            List<Method> defaultMethods = findConcreteMethodsOnInterfaces(clazz);
            if (defaultMethods != null) {
                result = new Method[declaredMethods.length + defaultMethods.size()];
                System.arraycopy(declaredMethods, 0, result, 0, declaredMethods.length);
                int index = declaredMethods.length;
                for (Method defaultMethod : defaultMethods) {
                    result[index] = defaultMethod;
                    index++;
                }
            } else {
                result = declaredMethods;
            }
            declaredMethodsCache.put(clazz, result);
        }
        return result;
    }

    /**
     * Find and return the specified value from the specified enum class.
     *
     * @param clazz The enum class to introspect
     * @param valueName The name of the enum value to get
     * @return The enum value
     */
    public Object getEnumValue(final Class<?> clazz, final String valueName) {
        if (clazz.isEnum()) {
            for (Object o : clazz.getEnumConstants()) {
                if (o.toString().equals(valueName)) {
                    return o;
                }
            }
            throw new IllegalArgumentException("Unable to get an enum constant with that name.");
        } else {
            throw new IllegalArgumentException(clazz + " must be an enum.");
        }
    }

    /**
     * Get the field represented by the supplied {@link Field field object} on
     * the specified {@link Object target object}. In accordance with
     * {@link Field#get(Object)} semantics, the returned value is automatically
     * wrapped if the underlying field has a primitive type.
     *
     * @param field The field to get
     * @param target The target object from which to get the field
     * @return The field's current value
     * @throws IllegalAccessException when unable to access the specified field because access modifiers prevent it
     */
    public Object getField(final Field field, final Object target) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * This variant retrieves {@link Class#getMethods()} from a local cache
     * in order to avoid the JVM's SecurityManager check and defensive array copying.
     *
     * @param clazz the class to introspect
     * @return the cached array of methods
     * @see Class#getMethods()
     */
    protected Method[] getMethods(final Class<?> clazz) {
        Method[] result = methodsCache.get(clazz);
        if (result == null) {
            result = clazz.getMethods();
            methodsCache.put(clazz, result);
        }
        return result;
    }

    /**
     * Get the field represented by the supplied {@link Field field object} on
     * the specified {@link Object target object}. In accordance with
     * {@link Field#get(Object)} semantics, the returned value is automatically
     * wrapped if the underlying field has a primitive type.
     *
     * @param field The field to get
     * @return The field's current value
     * @throws IllegalAccessException when unable to access the specified field because access modifiers prevent it
     */
    public Object getStaticField(final Field field) throws IllegalAccessException {
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Field must be static.");
        }
        return getField(field, null);
    }

    /**
     * Invoke the specified {@link Constructor}  with the supplied arguments.
     *
     * @param constructor The method to invoke
     * @param args The invocation arguments (may be <code>null</code>)
     * @return The invocation result, if any
     * @throws IllegalAccessException when unable to access the specified constructor because access modifiers prevent it
     * @throws java.lang.reflect.InvocationTargetException when a reflection invocation fails
     * @throws InstantiationException when an instantiation fails
     */
    public Object invokeConstructor(final Constructor constructor, final Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        if (constructor == null) {
            throw new IllegalArgumentException("Constructor must not be null.");
        }
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    /**
     * Invoke the specified {@link Method} against the supplied target object
     * with the supplied arguments. The target object can be <code>null</code>
     * when invoking a static {@link Method}.
     *
     * @param method The method to invoke
     * @param target The target object to invoke the method on
     * @param args The invocation arguments (may be <code>null</code>)
     * @return The invocation result, if any
     * @throws IllegalAccessException when unable to access the specified method because access modifiers prevent it
     * @throws java.lang.reflect.InvocationTargetException when a reflection invocation fails
     */
    public Object invokeMethod(final Method method, final Object target, final Object... args) throws InvocationTargetException, IllegalAccessException {
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null.");
        }
        if (target == null) {
            throw new IllegalArgumentException("Object must not be null.");
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    /**
     * Invoke the specified static {@link Method} with the supplied arguments.
     *
     * @param method The method to invoke
     * @param args The invocation arguments (may be <code>null</code>)
     * @return The invocation result, if any
     * @throws IllegalAccessException when unable to access the specified method because access modifiers prevent it
     * @throws java.lang.reflect.InvocationTargetException when a reflection invocation fails
     */
    public Object invokeStaticMethod(final Method method, final Object... args) throws InvocationTargetException, IllegalAccessException {
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null.");
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Method must be static.");
        }
        method.setAccessible(true);
        return method.invoke(null, args);
    }

}
