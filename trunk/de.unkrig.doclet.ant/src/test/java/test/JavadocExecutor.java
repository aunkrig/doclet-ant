
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.RunnableWhichThrows;
import de.unkrig.commons.lang.security.ExitCatcher;
import de.unkrig.commons.nullanalysis.Nullable;

/**
 * Executes the JAVADOC tool within the running JVM.
 */
public
class JavadocExecutor {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    /**
     * Helper bean for the {@code -linkoffline}" command-line option.
     */
    public static
    class OfflineLink {

        private final URL    extDocUrl;
        private final String packageListLoc;

        public
        OfflineLink(URL extDocUrl, String packageListLoc) {
            this.extDocUrl      = extDocUrl;
            this.packageListLoc = packageListLoc;
        }
    }
    
    @Nullable private File          destDir;
    @Nullable private String        docletClassName;
    @Nullable private File[]        docletPath;
    @Nullable private File[]        sourcepath;
    @Nullable private File[]        classpath;
    @Nullable private OfflineLink[] offlineLinks;
    @Nullable private File          antlibFile;
    @Nullable private File          overviewFile;

    public void setDestDir(File destDir)                     { this.destDir         = destDir;         }
    public void setDocletClassName(String docletClassName)   { this.docletClassName = docletClassName; }
    public void setDocletPath(File... docletPath)            { this.docletPath      = docletPath;      }
    public void setSourcepath(File... sourcepath)            { this.sourcepath      = sourcepath;      }
    public void setClasspath(File... classpath)              { this.classpath       = classpath;       }
    public void setOfflineLinks(OfflineLink... offlineLinks) { this.offlineLinks    = offlineLinks;    }
    public void setAntlibFile(File antlibFile)               { this.antlibFile      = antlibFile;      }
    public void setOverviewFile(File overviewFile)           { this.overviewFile    = overviewFile;    }

    /**
     * Executes the JAVADOC tool for a set of packages.
     */
    public void
    execute(String... packages) throws Exception {
        
        List<String> args = this.optionsToArgs();
        
        for (String p : packages) args.add(p);
        
        JavadocExecutor.execute2(args);
    }
    
    /**
     * Executes the JAVADOC tool for a set of source files.
     */
    public void
    execute(File... sourceFiles) throws Exception {
        
        List<String> args = this.optionsToArgs();
        
        for (File sf : sourceFiles) args.add(sf.getPath());
        
        JavadocExecutor.execute2(args);
    }
    
    private static void
    execute2(List<String> args) throws Exception {

        File toolsJar = new File(System.getProperty("java.home") + "/../lib/tools.jar");
        if (!toolsJar.exists()) {
            throw new IllegalStateException(
                "\"" + toolsJar + "\" does not exist in the $JAVA_HOME - please direct $JAVA_HOME to a JDK, not a JRE"
            );
        }
        
        @SuppressWarnings("resource") ClassLoader
        cl = new URLClassLoader(
            new URL[] { toolsJar.toURI().toURL() },
            ClassLoader.getSystemClassLoader()
        );

        Method mainMethod = cl.loadClass("com.sun.tools.javadoc.Main").getMethod("main", String[].class);

        ExitCatcher.catchExit(new RunnableWhichThrows<Exception>() {

            @Override public void
            run() throws Exception {
                try {
                    mainMethod.invoke(null, (Object) args.toArray(new String[args.size()]));
                } catch (InvocationTargetException ite) {
                    Throwable te = ite.getTargetException();
                    if (te instanceof Exception) throw (Exception) te;
                    throw ite;
                }
            }
        });
    }

    private List<String>
    optionsToArgs() {
        
        List<String> args = new ArrayList<String>();
        
        addFileOption("-d", this.destDir, args);
        addStringOption("-doclet", this.docletClassName, args);
        addPathOption("-docletpath", this.docletPath, args);
        addPathOption("-sourcepath", this.sourcepath, args);
        addPathOption("-classpath", this.classpath, args);
        if (this.offlineLinks != null) {
            for (OfflineLink lo : this.offlineLinks) {
                args.add("-linkoffline");
                args.add(lo.extDocUrl.toString());
                args.add(lo.packageListLoc);
            }
        }
        addFileOption("-antlib-file", this.antlibFile, args);
        addFileOption("-overview", this.overviewFile, args);
        
        return args;
    }

    private static void
    addStringOption(String option, @Nullable String argument, List<String> result) {
        
        if (argument == null) return;
        
        result.add(option);
        result.add(argument);
    }

    private static void
    addPathOption(String option, @Nullable File[] path, List<String> result) {
        
        if (path == null) return;
        
        result.add(option);
        result.add(joinPath(path));
    }

    private static void
    addFileOption(String option, @Nullable File file, List<String> result) {
    
        if (file == null) return;
        
        result.add(option);
        result.add(file.getPath());
    }

    private static String
    joinPath(File[] path) {
        
        StringBuilder result = new StringBuilder();
        
        result.append(path[0].getPath());
        
        for (int i = 1; i < path.length; i++) {
            result.append(File.pathSeparatorChar).append(path[i].getPath());
        }
        
        return result.toString();
    }
}
