
/*
 * de.unkrig.doclet.ant - A doclet which generates metadata documents for an APACHE ANT extension
 *
 * Copyright (c) 2017, Arno Unkrig
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import de.unkrig.commons.lang.protocol.RunnableWhichThrows;
import de.unkrig.commons.lang.security.ExitCatcher;
import de.unkrig.doclet.ant.AntDoclet;

public class AntologyTest {

    @Test
    public void test() throws Exception {

        String ws = "C:/workspaces/antology";
        String mr = "C:/Users/Arno/.m2/repository";
        String ps = System.getProperty("path.separator");
        String[] args = {
            "-d", "destdir",
            "-doclet", AntDoclet.class.getName(),
            "-docletpath", (
                ws + "/antology/target/classes"
                + ps + mr + "/org/apache/ant/ant/1.9.8/ant-1.9.8.jar"
            ),
            "-sourcepath", ws + "/antology/src/main/java",
            "-classpath",
            (
                ws + "/commons-doclet/target/classes"
                + ps + ws + "/commons-io/target/classes"
                + ps + ws + "/commons-lang/target/classes"
                + ps + ws + "/commons-net/target/classes"
                + ps + ws + "/commons-nullanalysis/target/classes"
                + ps + ws + "/commons-text/target/classes"
                + ps + ws + "/commons-util/target/classes"
                + ps + mr + "/org/apache/ant/ant/1.9.8/ant-1.9.8.jar"
                + ps + mr + "/commons-net/commons-net/1.4.0/commons-net-1.4.0.jar"
            ),
            "-linkoffline", "http://commons.unkrig.de/javadoc",                      ws + "/antology/package-lists/de.unkrig.commons",
            "-linkoffline", "https://commons.apache.org/proper/commons-net/apidocs", ws + "/antology/package-lists/org.apache.commons.net",
            "-linkoffline", "http://api.dpml.net/org/apache/ant/1.7.0",              ws + "/antology/package-lists/org.apache.ant",
            "-linkoffline", "https://docs.oracle.com/javase/8/docs/api",             ws + "/antology/package-lists/jre",

            "-antlib-file", ws + "/antology/src/main/resources/de/unkrig/antology/ant.xml",

            "-overview", ws + "/antology/src/main/javadoc/overview.html",

            "de.unkrig.antology",
            "de.unkrig.antology.condition",
            "de.unkrig.antology.filter",
            "de.unkrig.antology.task",
            "de.unkrig.antology.type",
            "de.unkrig.antology.util",
        };

        ClassLoader cl = new URLClassLoader(new URL[] {
            new URL("file:/" + System.getProperty("java.home") + "/lib/tools.jars")
        }, ClassLoader.getSystemClassLoader());

        Method mainMethod = cl.loadClass("com.sun.tools.javadoc.Main").getMethod("main", String[].class);

        ExitCatcher.catchExit(new RunnableWhichThrows<Exception>() {

            @Override public void
            run() throws Exception {
                try {
                    mainMethod.invoke(null, (Object) args);
                } catch (InvocationTargetException ite) {
                    Throwable te = ite.getTargetException();
                    if (te instanceof Exception) throw (Exception) te;
                    throw ite;
                }
            }
        });
    }
}
