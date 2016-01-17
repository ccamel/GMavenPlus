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
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;


/**
 * Unit tests for the ClassWrangler class.
 *
 * @author Keegan Witt
 */
public class ClassWranglerTest {

    private ClassWrangler classWrangler;

    @Before
    public void init() {
        classWrangler = spy(new ClassWrangler());
        classWrangler.log = mock(Log.class);
    }

    @Test
    public void testGetGroovyJar() throws Exception {
        doThrow(new ClassNotFoundException("Throwing exception to force GMavenPlus to get version from jar.")).when(classWrangler).getClass(anyString());
        doReturn("some/path/groovy-all-1.5.0.jar").when(classWrangler).getJarPath();
        assertEquals("groovy-all-1.5.0.jar", classWrangler.getGroovyJar());
    }

    @Test
    public void testGetGroovyVersionStringFromGroovySystem() throws Exception {
        doReturn(GroovySystem.class).when(classWrangler).getClass(anyString());
        assertEquals("1.5.0", classWrangler.getGroovyVersionString());
    }

    @Test
    public void testGetGroovyVersionStringFromInvokerHelper() throws Exception {
        doThrow(new ClassNotFoundException("Throwing exception to force GMavenPlus to get version from InvokerHelper.")).doReturn(InvokerHelper.class).when(classWrangler).getClass(anyString());
        assertEquals("1.5.0", classWrangler.getGroovyVersionString());
    }

    @Test
    public void testGetGroovyVersionStringFromJar() throws Exception {
        doThrow(new ClassNotFoundException("Throwing exception to force GMavenPlus to get version from jar.")).when(classWrangler).getClass(anyString());
        doReturn("some/path/groovy-all-1.5.0.jar").when(classWrangler).getJarPath();
        assertEquals("1.5.0", classWrangler.getGroovyVersionString());
    }

    @Test
    public void testGetGroovyVersionWithIndyFromJar() throws Exception {
        doThrow(new ClassNotFoundException("Throwing exception to force GMavenPlus to get version from jar.")).when(classWrangler).getClass(anyString());
        doReturn("some/path/groovy-all-2.4.0-indy.jar").when(classWrangler).getJarPath();
        assertEquals("2.4.0", classWrangler.getGroovyVersion().toString());
    }

    @Test
    public void testGetGroovyVersionWithGrooidFromJar() throws Exception {
        doReturn("some/path/groovy-all-2.4.0-grooid.jar").when(classWrangler).getJarPath();
        assertEquals("2.4.0", classWrangler.getGroovyVersion().toString());
    }

    @Test
    public void testIsGroovyIndyTrue() throws Exception {
        doReturn(null).when(classWrangler).getClass(anyString());  // make it appear Groovy is indy
        assertTrue(classWrangler.isGroovyIndy());
    }

    @Test
    public void testIsGroovyIndyFalse() throws Exception {
        doThrow(new ClassNotFoundException("Throwing exception to make it appear Groovy is not indy.")).when(classWrangler).getClass(anyString());
        assertFalse(classWrangler.isGroovyIndy());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindConstructorClassNull() {
        classWrangler.findConstructor(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindConstructorNotFound() {
        classWrangler.findConstructor(TestClass.class, TestClass.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindFieldClassNull() {
        classWrangler.findField(null, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindFieldNameAndTypeNull() {
        classWrangler.findField(TestClass.class, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindFieldNotFound() {
        classWrangler.findField(TestClass.class, "nonExistentField", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindMethodClassNull() {
        classWrangler.findMethod(null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindMethodNameNull() {
        classWrangler.findMethod(TestClass.class, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindMethodNotFound() {
        classWrangler.findMethod(TestClass.class, "nonExistentMethod");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetEnumConstantNonEnumClass() {
        classWrangler.getEnumValue(TestClass.class, "VALUE");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetEnumConstantValueNotFound() {
        classWrangler.getEnumValue(TestClass.ENUM.class, "NON_EXISTENT_VALUE");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetStaticFieldNotStatic() throws Exception {
        classWrangler.getStaticField(classWrangler.findField(TestClass.class, "stringField", String.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvokeConstructorNull() throws Exception {
        classWrangler.invokeConstructor(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvokeMethodMethodNull() throws Exception {
        classWrangler.invokeMethod(null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvokeMethodObjectNull() throws Exception {
        classWrangler.invokeMethod(TestClass.class.getMethod("getStringField"), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvokeStaticMethodMethodNull() throws Exception {
        classWrangler.invokeStaticMethod(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvokeStaticMethodMethodNotStatic() throws Exception {
        classWrangler.invokeStaticMethod(TestClass.class.getMethod("getStringField"));
    }

    @Test
    public void testConstructor() throws Exception {
        classWrangler.invokeConstructor(classWrangler.findConstructor(ClassWrangler.class));
    }

    @Test
    public void testHappyPaths() throws Exception {
        String expectedString = "some string";
        Object test1 = classWrangler.invokeConstructor(classWrangler.findConstructor(TestClass.class));
        classWrangler.invokeMethod(classWrangler.findMethod(TestClass.class, "setStringField", String.class), test1, expectedString);
        assertEquals(expectedString, classWrangler.invokeMethod(classWrangler.findMethod(TestClass.class, "getStringField"), test1));
        assertEquals(TestClass.HELLO_WORLD, classWrangler.invokeStaticMethod(classWrangler.findMethod(TestClass.class, "helloWorld")));
        assertEquals(TestClass.ENUM.VALUE, classWrangler.getEnumValue(TestClass.ENUM.class, "VALUE"));
        assertEquals(TestClass.HELLO_WORLD, classWrangler.getStaticField(classWrangler.findField(TestClass.class, "HELLO_WORLD", null)));
        Object test2 = classWrangler.invokeConstructor(classWrangler.findConstructor(TestClass.class, String.class), expectedString );
        assertEquals(expectedString, classWrangler.getField(classWrangler.findField(TestClass.class, "stringField", String.class), test2));
    }

    public static class TestClass {
        public static final String HELLO_WORLD = "Hello world!";
        public String stringField;

        public TestClass() { }

        public TestClass(String newStringField) {
            stringField = newStringField;
        }

        public String getStringField() {
            return stringField;
        }

        public void setStringField(String newStringField) {
            stringField = newStringField;
        }

        public static String helloWorld() {
            return HELLO_WORLD;
        }

        protected static enum ENUM {
            VALUE
        }
    }

    public static class GroovySystem {
        public static String getVersion() {
            return "1.5.0";
        }
    }

    public static class InvokerHelper {
        public static String getVersion() {
            return "1.5.0";
        }
    }

}
