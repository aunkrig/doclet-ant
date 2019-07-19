
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

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import de.unkrig.commons.file.FileUtil;
import test.JavadocExecutor.OfflineLink;

public
class AntologyTest {

    @Test public void
    test() throws Exception {

        File destDir = new File("destdir");
        
        if (destDir.exists()) FileUtil.deleteRecursively(destDir);
        
        String ws = "..";

        JavadocExecutor je = new JavadocExecutor();
        
        je.setDestDir(destDir);
        je.setDocletClassName("de.unkrig.doclet.ant.AntDoclet");
        je.setDocletPath(
            assertIsDirectory(new File("target/classes")),
            assertIsDirectory(new File(ws + "/antology/target/classes")), // For antology's "enumerated attributes".
            getClassPathEntry("/org/apache/ant/ant/1.9.8/ant-1.9.8.jar")
        );

        je.setSourcepath(assertIsDirectory(new File(ws + "/antology/src/main/java")));
        
        je.setClasspath(
            assertIsFile(new File(ws + "/antology/target/antology-2.0.6-SNAPSHOT-jar-with-dependencies.jar")),
            getClassPathEntry("/org/apache/ant/ant/1.9.8/ant-1.9.8.jar"),
            getClassPathEntry("/commons-net/commons-net/1.4.0/commons-net-1.4.0.jar")
        );
        je.setAntlibFile(assertIsFile(new File(ws + "/antology/src/main/resources/de/unkrig/antology/ant.xml")));
        je.setOverviewFile(assertIsFile(new File(ws + "/antology/src/main/javadoc/overview.html")));
        je.setOfflineLinks( // SUPPRESS CHECKSTYLE LineLength:5
            new OfflineLink(new URL("http://commons.unkrig.de/commons-text/apidocs"),         ws + "/antology/package-lists/de.unkrig.commons-text"),
            new OfflineLink(new URL("http://commons.unkrig.de/commons-util/apidocs"),         ws + "/antology/package-lists/de.unkrig.commons-util"),
            new OfflineLink(new URL("https://commons.apache.org/proper/commons-net/apidocs"), ws + "/antology/package-lists/org.apache.commons.net"),
            new OfflineLink(new URL("http://api.dpml.net/org/apache/ant/1.7.0"),              ws + "/antology/package-lists/org.apache.ant"),
            new OfflineLink(new URL("https://docs.oracle.com/javase/8/docs/api"),             ws + "/antology/package-lists/jre")
        );

        je.execute(
            "de.unkrig.antology",
            "de.unkrig.antology.condition",
            "de.unkrig.antology.filter",
            "de.unkrig.antology.task",
            "de.unkrig.antology.type",
            "de.unkrig.antology.util"
        );

        // Verify that the "usual" ANTDOC output files and directories were created.
        Assert.assertEquals(
            new HashSet<String>(Arrays.asList(
                "chainableReaders",
                "conditions",
                "resourceCollections",
                "tasks",
                "alldefinitions-frame.html",
                "alldefinitions-noframe.html",
                "index.html",
                "overview-summary.html",
                "package-list",
                "stylesheet.css",
                "stylesheet2.css"
            )),
            new HashSet<String>(Arrays.asList(destDir.list()))
        );
        
//        FileUtil.deleteRecursively(destDir);
    }

    private static File
    assertIsFile(File file) {
        Assert.assertTrue(file + " is not a normal file", file.isFile());
        return file;
    }

    private static File
    assertIsDirectory(File file) {
        Assert.assertTrue(file + " is not a directory", file.isDirectory());
        return file;
    }

    private static File
    getClassPathEntry(String classpathEntryNameSuffix) {
        
        classpathEntryNameSuffix = new File(classpathEntryNameSuffix).getPath();
        
        for (File cpe : CLASSPATH_ENTRIES) {
            if (cpe.getPath().endsWith(classpathEntryNameSuffix)) return cpe;
        }
        
        Assert.fail((
            "Entry suffix\""
            + classpathEntryNameSuffix
            + "\" not found on classpath \""
            + CLASSPATH_ENTRIES
            + "\""
        ));
        
        return new File("???");
    }
    
    private static final List<File> CLASSPATH_ENTRIES = new ArrayList<File>();
    static {
        for (String s : System.getProperty("java.class.path").split(File.pathSeparator)) {
            CLASSPATH_ENTRIES.add(new File(s));
        }
    }
}
