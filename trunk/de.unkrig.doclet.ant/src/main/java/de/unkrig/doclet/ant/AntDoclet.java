
/*
 * de.unkrig.doclet.ant - A doclet which generates metadata documents for an APACHE ANT extension
 *
 * Copyright (c) 2015, Arno Unkrig
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

package de.unkrig.doclet.ant;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.LanguageVersion;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.Tags;
import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.doclet.html.Html.Link;
import de.unkrig.commons.doclet.html.Html.LinkMaker;
import de.unkrig.commons.io.IoUtil;
import de.unkrig.commons.io.IoUtil.CollisionStrategy;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.ExceptionUtil;
import de.unkrig.commons.lang.ObjectUtil;
import de.unkrig.commons.lang.StringUtil;
import de.unkrig.commons.lang.protocol.ConsumerWhichThrows;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.lang.protocol.Mapping;
import de.unkrig.commons.lang.protocol.Mappings;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.commons.text.Notations;
import de.unkrig.commons.text.xml.XmlUtil;
import de.unkrig.commons.util.CommandLineOptions;
import de.unkrig.commons.util.annotation.CommandLineOption;
import de.unkrig.commons.util.annotation.CommandLineOptionGroup;
import de.unkrig.commons.util.collections.IterableUtil;
import de.unkrig.commons.util.collections.IterableUtil.ElementWithContext;
import de.unkrig.doclet.ant.templates.AllDefinitionsHtml;
import de.unkrig.doclet.ant.templates.IndexHtml;
import de.unkrig.doclet.ant.templates.OverviewSummaryHtml;
import de.unkrig.doclet.ant.templates.TypeHtml;
import de.unkrig.notemplate.NoTemplate;
import de.unkrig.notemplate.javadocish.Options;

/**
 * A doclet that generates documentation for <a href="http://ant.apache.org">APACHE ANT</a> tasks and other artifacts.
 *
 * @see <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
 *      Overview"</a>
 */
public // SUPPRESS CHECKSTYLE HideUtilityClassConstructor
class AntDoclet {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    // ======================= CONSTANTS =======================

    private static final String DEFAULTS_PROPERTIES_RESOURCE_NAME = "org/apache/tools/ant/taskdefs/defaults.properties";

    private static final Pattern ADD_TEXT_METHOD_NAME          = Pattern.compile("addText");
    private static final Pattern SET_ATTRIBUTE_METHOD_NAME     = Pattern.compile("set(?<attributeName>[A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME               = Pattern.compile("add(?:Configured)?");
    private static final Pattern ADD_SUBELEMENT_METHOD_NAME    = Pattern.compile("add(?:Configured)?(?<subelementName>[A-Z]\\w*)"); // SUPPRESS CHECKSTYLE LineLength
    private static final Pattern CREATE_SUBELEMENT_METHOD_NAME = Pattern.compile("create(?<subelementName>[A-Z]\\w*)");

    // ======================= CONFIGURATION FIELDS =======================

    private enum Theme { JAVA7, JAVA8 }

    // SUPPRESS CHECKSTYLE ConstantName:8
    private static RootDoc                                rootDoc          = ObjectUtil.almostNull();
    private static final Options                          options          = new Options();
    private static final Collection<String>               antlibResources  = new ArrayList<>();
    private static final Collection<File>                 antlibFiles      = new ArrayList<>();
    private static final Map<String /*packageName*/, URL> externalJavadocs = new HashMap<>();
    private static Theme                                  theme            = Theme.JAVA8;
    private static File[]                                 sourcePath       = { new File(".") };
    private static File[]                                 classPath        = new File[0];

    private static Mapping<ClassDoc, Link> externalAntdocs = ObjectUtil.almostNull();

    private static Mapping<ClassDoc, Link>
    getAntTypes(RootDoc rootDoc) {

        Map<ClassDoc, Link> m = new HashMap<>();

        // Resource "de/unkrig/doclet/ant/AntDoclet/external-antdocs.properties" provides a number of
        // qualified-class-name to label/href mappings.
        {
            Properties properties;
            try {
                properties = AntDoclet.loadPropertiesFromResource((
                    AntDoclet.class.getPackage().getName().replace('.', '/')
                    + "/external-antdocs.properties"
                ));
            } catch (IOException ioe) {
                throw new AssertionError(null, ioe);
            }

            for (Entry<Object, Object> e : properties.entrySet()) {
                String qualifiedClassName = (String) e.getKey();
                String tmp                = (String) e.getValue();

                String label = tmp.substring(0, tmp.lastIndexOf(' ')).trim();
                String href  = tmp.substring(tmp.lastIndexOf(' ') + 1).trim();

                ClassDoc cd = rootDoc.classNamed(qualifiedClassName);
                if (cd == null) {

                    // Classes that are not referenced are not loaded by JAJADOC!
                    continue;
                }

                m.put(cd, new Link(href, label));
            }
        }

        // Determine the documentation URLs for the ANT standard tasks.
        {
            Properties properties;
            {
                try {
                    properties = AntDoclet.loadPropertiesFromResource(AntDoclet.DEFAULTS_PROPERTIES_RESOURCE_NAME);
                } catch (IOException ioe) {
                    throw ExceptionUtil.wrap((
                        "Could not open resource \""
                        + AntDoclet.DEFAULTS_PROPERTIES_RESOURCE_NAME
                        + "\"; make sure that \"ant.jar\" is on the doclet's classpath"
                    ), ioe, ExceptionInInitializerError.class);
                }
            }

            for (Entry<Object, Object> e : properties.entrySet()) {
                String taskName           = (String) e.getKey();
                String qualifiedClassName = (String) e.getValue();

                ClassDoc cd = rootDoc.classNamed(qualifiedClassName);
                if (cd == null) {

                    // "defaults.properties" declares many "optional" tasks, so let's not complain if the task is
                    // not on the classpath.
                    //rootDoc.printError("Cannot load task \"" + qualifiedClassName + "\" for external HREF");
                    continue;
                }

                m.put(cd, new Link(
                    "<code>&lt;" + taskName + "&gt;</code>",
                    "http://ant.apache.org/manual/Tasks/" + taskName + ".html"
                ));
            }
        }

        // Determine the documentation URLs for the ANT standard types.
        {
            Properties properties;
            try {
                properties = AntDoclet.loadPropertiesFromResource("org/apache/tools/ant/types/defaults.properties");
            } catch (IOException ioe) {
                throw ExceptionUtil.wrap(
                    "Make sure that \"ant.jar\" is on the doclet's classpath",
                    ioe,
                    ExceptionInInitializerError.class
                );
            }

            for (Entry<Object, Object> e : properties.entrySet()) {
                String typeName           = (String) e.getKey();
                String qualifiedClassName = (String) e.getValue();

                ClassDoc cd = rootDoc.classNamed(qualifiedClassName);
                if (cd == null) {

                    // "defaults.properties" declares many "optional" types, so let's not complain if the type is
                    // not on the classpath.
                    //rootDoc.printError("Cannot load type \"" + qualifiedClassName + "\" for external HREF");
                    continue;
                }

                m.put(cd, new Link(
                    "<code>&lt;" + typeName + "&gt;</code>",
                    "http://ant.apache.org/manual/Types/" + typeName + ".html"
                ));
            }
        }

        return Mappings.fromMap(m);
    }

    // ======================= CONFIGURATION SETTERS =======================

    /**
     * Where to create documentation in HTML format (optional).
     * <p>
     *   The effective file name is:
     * </p>
     * <p>
     *   {@code <dest-dir>/<ant-type-group>/<ant-type>.html}
     * </p>
     * <p>
     *   The default destination directory is "{@code .}".
     * </p>
     */
    @CommandLineOption(name = "-d") public static void
    setDestination(File directory) { AntDoclet.options.destination = directory; }

    /**
     * Splits the index file into multiple files, alphabetically, one file per letter, plus a file for any index
     * entries that start with non-alphabetical characters.
     */
    @CommandLineOption(name = "-splitindex") public static void
    setSplitIndex() { AntDoclet.options.splitIndex = true; }

    /**
     * The charset to use when writing the HTML files. The default is the JVM default charset, "${file.encoding}".
     */
    @CommandLineOption(name = "-docencoding") public static void
    setDocEncoding(Charset charset) { AntDoclet.options.documentCharset = charset; }

    /**
     * The HTML character set for this document.
     * <p>
     *   If set, then the following tag appears in the {@code <head>} of all generated documents:
     * </p>
     * <p>
     *   {@code <meta http-equiv="Content-Type" content="text/html; charset="}<var>charset</var>{@code ">}
     * </p>
     */
    @CommandLineOption(name = "-charset") public static void
    setCharset(String name) { AntDoclet.options.htmlCharset = name; }

    /**
     * The title to place near the top of the overview summary file. The text specified in the title tag is placed as
     * a centered, level-one heading directly beneath the top navigation bar. The title tag can contain HTML tags and
     * white space.
     */
    @CommandLineOption(name = "-doctitle") public static void
    setDocTitle(String htmlText) { AntDoclet.options.docTitle = htmlText; }

    /**
     * The header text to be placed at the top of each output file. The header is placed to the right of the upper
     * navigation bar. The header can contain HTML tags and white space.
     */
    @CommandLineOption(name = "-header") public static void
    setHeader(String text) { AntDoclet.options.header = text; }

    /**
     * The footer text to be placed at the bottom of each output file. The footer value is placed to the right of the
     * lower navigation bar. The footer value can contain HTML tags and white space.
     */
    @CommandLineOption(name = "-footer") public static void
    setFooter(String text) { AntDoclet.options.footer = text; }

    /**
     * The text to be placed at the top of each output file.
     */
    @CommandLineOption(name = "-top") public static void
    setTop(String text) { AntDoclet.options.top = text; }

    /**
     * The text to be placed at the bottom of each output file. The text is placed at the bottom of the page,
     * underneath the lower navigation bar. The text can contain HTML tags and white space.
     */
    @CommandLineOption(name = "-bottom") public static void
    setBottom(String text) { AntDoclet.options.bottom = text; }

    /**
     * Suppress normal output.
     */
    @CommandLineOption(name = "-quiet") public static void
    setQuiet() { AntDoclet.options.quiet = true; }

    /**
     * Suppresses the time stamp, which is hidden in an HTML comment in the generated HTML near the top of each page.
     * This is useful when you want to run the javadoc command on two source bases and get the differences between diff
     * them, because it prevents time stamps from causing a diff (which would otherwise be a diff on every page).
     * The time stamp includes the javadoc command release number.
     */
    @CommandLineOption(name = "-notimestamp") public static void
    setNoTimestamp() { AntDoclet.options.noTimestamp = true; }

    @CommandLineOptionGroup(cardinality = CommandLineOptionGroup.Cardinality.ONE_OR_MORE)
    interface AntlibGroup {}

    /**
     * The ANTLIB file to parse, see <a href="https://ant.apache.org/manual/Types/antlib.html">the documentation of
     * the ANTLIB concept</a>.
     */
    @CommandLineOption(name = "-antlib-file", group = AntlibGroup.class) public static void
    addAntlibFile(File file) { AntDoclet.antlibFiles.add(file); }

    /**
     * The name of an ANTLIB resource to parse, see <a href="https://ant.apache.org/manual/Types/antlib.html">the
     * documentation of the ANTLIB concept</a>.
     */
    @CommandLineOption(name = "-antlib-resource", group = AntlibGroup.class) public static void
    addAntlibResource(String name) { AntDoclet.antlibResources.add(name); }

    /**
     * See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDEDJFI">the JAVADOC
     * tool documentation for the "-link" command line option</a>.
     */
    @CommandLineOption(name = "-link") public static void
    addLink(String externalDocumentationUrl) throws IOException {

        if (!externalDocumentationUrl.endsWith("/")) externalDocumentationUrl += "/";

        URL externalDocumentationUrl2 = new URL(
            new URL("file", null, -1, AntDoclet.options.destination.toString()),
            externalDocumentationUrl
        );

        Docs.readExternalJavadocs(
            externalDocumentationUrl2,  // targetUrl
            externalDocumentationUrl2,  // packageListUrl
            AntDoclet.externalJavadocs, // externalJavadocs
            AntDoclet.rootDoc
        );
    }

    /**
     * See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDFIIJH">the JAVADOC
     * tool documentation for the "-linkofflin" command line option</a>.
     */
    @CommandLineOption(name = "-linkoffline") public static void
    addLinkOffline(String externalDocumentationUrl, String packageListLocation) throws IOException {

        if (!externalDocumentationUrl.endsWith("/")) externalDocumentationUrl += "/";
        if (!packageListLocation.endsWith("/"))      packageListLocation      += "/";

        URL externalDocumentationUrl2 = new URL(
            new URL("file", null, -1, AntDoclet.options.destination.toString()),
            externalDocumentationUrl
        );

        URL packageListUrl = (
            packageListLocation.startsWith("http:") || packageListLocation.startsWith("file:")
            ? new URL(new URL("file", null, -1, System.getProperty("user.dir")), packageListLocation)
            : new URL("file", null, new File(packageListLocation).getAbsolutePath() + '/')
        );
        Docs.readExternalJavadocs(
            externalDocumentationUrl2,  // targetUrl
            packageListUrl,             // packageListUrl
            AntDoclet.externalJavadocs, // externalJavadocs
            AntDoclet.rootDoc
        );
    }

    /**
     * For compatibility with the standard JAVADOC doclet; ignored.
     */
    @CommandLineOption(name = "-tag") public static void
    addTag(String spec) {}

    /**
     * Which style sheets and resources to use.
     *
     * @param theme JAVA7|JAVA8
     */
    @CommandLineOption(name = "-theme") public static void
    setTheme(Theme theme) { AntDoclet.theme = theme; }

    /**
     * Takes also effect for loading ANTLIB resources.
     */
    @CommandLineOption(name = "-sourcepath") public static void
    setSourcePath(String sourcePath) { AntDoclet.sourcePath = AntDoclet.parsePath(sourcePath); }

    /**
     * Takes also effect for loading ANTLIB resources.
     */
    @CommandLineOption(name = "-classpath") public static void
    setClassPath(String classPath) { AntDoclet.classPath = AntDoclet.parsePath(classPath); }

    /**
     * See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDBIEEI">the JAVADOC
     * tool documentation for the "-windowtitle" command line option</a>.
     */
    @CommandLineOption(name = "-windowtitle") public static void
    setWindowTitle(String text) { AntDoclet.options.windowTitle = text; }

    // ======================= END CONFIGURATION SETTERS =======================

    private static Properties
    loadPropertiesFromResource(String resourceName) throws IOException {

        InputStream is = AntDoclet.class.getClassLoader().getResourceAsStream(resourceName);
        if (is == null) throw new FileNotFoundException(resourceName);

        try {
            final Properties properties = new Properties();
            properties.load(is);
            is.close();
            return properties;
        } finally {
            try { is.close(); } catch (Exception e) {}
        }
    }

    /**
     * Representation of a "group of types", e.g. "tasks", "chainable readers", etc.
     */
    public static final
    class AntTypeGroup {

        /**
         * E.g. {@code "tasks"}
         */
        public final String subdir;

        /**
         * E.g. {@code "Task"}
         */
        public final String name;

        /**
         * E.g. {@code "Tasks"}
         */
        public final String heading;

        /**
         * E.g. <code>"Task &amp;quot;&amp;lt;{0}&amp;gt;&amp;quot;"</code>
         */
        public final MessageFormat typeTitleMf;

        /**
         * E.g. {@code "Task &amp;quot;&lt;code>&lt;{0}&gt;&lt;/code>&amp;quot;"}
         */
        public final MessageFormat typeHeadingMf;

        /**
         * The ANT types that comprise the group.
         */
        public final List<AntType> types = new ArrayList<>();

        /**
         * @param subdir        E.g. {@code "tasks"}
         * @param name          E.g. {@code "Task"}; may contain inline tags
         * @param heading       E.g. {@code "Tasks"}; may contain inline tags
         * @param typeTitleMf   Must not contain any inline tags; "<code>{0}</code>" maps to the type name
         * @param typeHeadingMf May contain inline tags; "<code>{0}</code>" maps to the type name
         */
        public
        AntTypeGroup(
            String subdir,
            String name,
            String heading,
            String typeTitleMf,
            String typeHeadingMf
        ) {
            this.subdir        = subdir;
            this.name          = name;
            this.heading       = heading;
            this.typeTitleMf   = new MessageFormat(typeTitleMf);
            this.typeHeadingMf = new MessageFormat(typeHeadingMf);
        }

        @Override public String
        toString() {
            return this.subdir;
        }
    }

    /**
     * Representation of an "ANT type", see <a href="http://ant.apache.org/manual/index.html">Concepts and Types</a>.
     * (An ANT task is a special case of an ANT type.)
     */
    public static final
    class AntType {

        public final String              name;
        public final ClassDoc            classDoc;
        @Nullable private ClassDoc       adaptTo;
        @Nullable public final MethodDoc characterData;
        public final List<AntAttribute>  attributes;
        public final List<AntSubelement> subelements;

        /**
         * @param adaptTo       The value of the "{@code adaptTo="..."}" attribute, see <a
         *                      href="http://ant.apache.org/manual/Tasks/typedef.html">here</a>
         * @param characterData The {@link MethodDoc} of the (optional) "{@code addText(String)}" method that ANT
         *                      invokes for text nested between the start end end tags
         */
        public
        AntType(
            String              name,
            ClassDoc            classDoc,
            @Nullable ClassDoc  adaptTo,
            @Nullable MethodDoc characterData,
            List<AntAttribute>  attributes,
            List<AntSubelement> subelements
        ) {
            this.name          = name;
            this.classDoc      = classDoc;
            this.adaptTo       = adaptTo;
            this.characterData = characterData;
            this.attributes    = attributes;
            this.subelements   = subelements;
        }

        @Override public String
        toString() {
            return "<" + this.name + " " + this.attributes + ">";
        }
    }

    /**
     * Representation of an attribute of an {@link AntType ANT type}.
     */
    public static final
    class AntAttribute {

        public final String           name;
        public final MethodDoc        methodDoc;
        public final Type             type;
        @Nullable public final String group;

        public
        AntAttribute(String name, MethodDoc methodDoc, Type type, @Nullable String group) {
            this.name      = name;
            this.methodDoc = methodDoc;
            this.type      = type;
            this.group     = group;
        }

        @Override public String
        toString() {
            return this.name + "=" + this.type;
        }
    }

    /**
     * Representation of a subelement of an ANT type (described in the ANT documentation as "Parameters specified as
     * nested elements").
     */
    public static final
    class AntSubelement {

        public final MethodDoc methodDoc;

        /**
         * {@code null} for a "typed subelement" (e.g. "{@code addConfigured(Task)}"); non-{@code null} for a "named
         * subelement" (e.g. "{@code addConfiguredFirstName(String)}").
         */
        @Nullable public final String name;

        public final Type             type;
        @Nullable public final String group;

        public
        AntSubelement(MethodDoc methodDoc, @Nullable String name, Type type, @Nullable String group) {
            this.methodDoc = methodDoc;
            this.name      = name;
            this.type      = type;
            this.group     = group;
        }
    }

    public static LanguageVersion languageVersion() { return LanguageVersion.JAVA_1_5; }

    /**
     * See <a href="https://docs.oracle.com/javase/6/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static int
    optionLength(String option) throws IOException {

        if ("-help".equals(option)) {
            CommandLineOptions.printResource(
                AntDoclet.class,
                "start(RootDoc).txt",
                Charset.forName("UTF-8"),
                System.out
            );
            return 1;
        }

        Method m = CommandLineOptions.getMethodForOption(option, AntDoclet.class);

        return m == null ? 0 : 1 + m.getParameterTypes().length;
    }

    /**
     * A doclet that generates documentation for <a href="http://ant.apache.org">APACHE ANT</a> tasks and other
     * artifacts.
     * <p>
     *   Opens, reads and parses <a href="http://ant.apache.org/manual/Types/antlib.html">ANTLIB files and
     *   resources</a>, and generates one document per type, task, macro, preset and script defined therein.
     * </p>
     *
     * <h3>Doclet command line options:</h3>
     *
     * <dl>
     * {@main.commandLineOptions}
     * </dl>
     *
     * <h3>Supported tags:</h3>
     *
     * <p>
     *   The following block tags may appear in the DOC comment of a class declaration that maps to an ANT type:
     * </p>
     * <dl>
     *   <dt><code>{&#64;ant.typeGroupName <var>type-group-name</var>}</code></dt>
     *   <dd>
     *     The name of the "type group" that the type belongs to, e.g. "{@code Task}".
     *   </dd>
     *   <dt><code>{&#64;ant.typeGroupSubdir <var>dir-name</var>}</code></dt>
     *   <dd>
     *     The name of the subdirectory that contains the documentation of all ANT types of the type group; e.g.
     *     "{@code tasks}".
     *   </dd>
     *   <dt><code>{&#64;ant.typeGroupHeading <var>text</var>}</code></dt>
     *   <dd>
     *     The heading to display above the list of types; e.g. "{@code Tasks}".
     *   </dd>
     *   <dt><code>{&#64;ant.typeTitleMf <var>message-format</var>}</code></dt>
     *   <dd>
     *     The message format to use to render the heading on each type details page; e.g. <code>"Task
     *     &amp;quot;&amp;lt;{0}&amp;gt;&amp;quot;"</code>
     *   </dd>
     *   <dt><code>{&#64;ant.typeHeadingMf <var>message-format</var>}</code></dt>
     *   <dd>
     *     The message format to use to render the title (i.e. the tooltip) of the heading on each type details page;
     *     e.g. {@code "Task &amp;quot;&lt;code>&lt;{0}&gt;&lt;/code>&amp;quot;"}
     *   </dd>
     *   <dt><code>{&#64;ant.group <var>group-name</var>}</code></dt>
     *   <dd>
     *     Attributes (or subelements) with equal <var>group-name</var> are grouped, and the <var>group-name</var> is
     *     rendered as a heading above the group.
     *   </dd>
     *   <dt><code>{&#64;ant.subelementOrder inheritedFirst}</code></dt>
     *   <dd>
     *     Enforces that the subelements <em>inherited</em> from superclasses appear <em>before</em> the non-inherited.
     *     The default is that the subelements in their "natural" order.
     *   </dd>
     * </dl>
     */
    public static boolean
    start(final RootDoc rootDoc) throws Exception {

        AntDoclet.rootDoc = rootDoc;

        AntDoclet.externalAntdocs = AntDoclet.getAntTypes(rootDoc);

        // Apply the doclet options.
        for (String[] option : rootDoc.options()) {

            Method m = CommandLineOptions.getMethodForOption(option[0], AntDoclet.class);

            // It is quite counterintuitive, but "RootDoc.options()" returns ALL options, not only those which
            // qualified by 'optionLength()'.
            if (m == null) continue;

            int res;
            try {
                res = CommandLineOptions.applyCommandLineOption(option[0], m, option, 1, null);
            } catch (Exception e) {
                throw ExceptionUtil.wrap("Parsing command line option \"" + option[0] + "\"", e, IOException.class);
            }
            assert res == option.length;
        }

        if (AntDoclet.antlibResources.isEmpty() && AntDoclet.antlibFiles.isEmpty()) {
            rootDoc.printWarning("Neither \"-antlib-resource\" nor \"-antlib-file\" option given");
        }

        switch (AntDoclet.theme) {

        case JAVA7:
            String resourceNamePrefix = "de/unkrig/doclet/ant/theme/java7/";
            for (String resourceNameSuffix : new String[] {
                "stylesheet.css",
                "resources/background.gif",
                "resources/tab.gif",
                "resources/titlebar_end.gif",
                "resources/titlebar.gif",
            }) {
                File file = new File(AntDoclet.options.destination, resourceNameSuffix);
                IoUtil.copyResource(
                    AntDoclet.class.getClassLoader(),
                    resourceNamePrefix + resourceNameSuffix,
                    file,
                    true // createMissingParentDirectories
                );
            }
            break;

        case JAVA8:
            IoUtil.copyResource(
                AntDoclet.class.getClassLoader(),
                "de/unkrig/doclet/ant/theme/java8/stylesheet.css",
                new File(AntDoclet.options.destination, "stylesheet.css"),
                true // createMissingParentDirectories
            );
            break;
        }

        IoUtil.copyResource(
            AntDoclet.class.getClassLoader(),
            "de/unkrig/doclet/ant/templates/stylesheet2.css",
            new File(AntDoclet.options.destination, "stylesheet2.css"),
            true // createMissingParentDirectories
        );

        // Render "index.html" (the frameset).
        NoTemplate.render(
            IndexHtml.class,
            new File(AntDoclet.options.destination, "index.html"),
            (IndexHtml indexHtml) -> { indexHtml.render(AntDoclet.options); },
            true,
            AntDoclet.options.documentCharset,
            AntDoclet.options.quiet
        );

        final LinkedHashMap<ClassDoc, AntTypeGroup> antTypeGroups = new LinkedHashMap<>();
        antTypeGroups.put(AntDoclet.rootDoc.classNamed("org.apache.tools.ant.Task"), new AntTypeGroup(
            "tasks",
            "Task", // typeGroupName
            "Tasks",
            "Task \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.put(
            AntDoclet.rootDoc.classNamed("org.apache.tools.ant.types.ResourceCollection"),
            new AntTypeGroup(
                "resourceCollections",
                "Resource collection", // typeGroupName
                "Resource collections",
                "Resource collection \"&lt;{0}&gt;\"",
                "<code>&lt;{0}&gt;</code>"
            )
        );
        antTypeGroups.put(
            AntDoclet.rootDoc.classNamed("org.apache.tools.ant.filters.ChainableReader"),
            new AntTypeGroup(
                "chainableReaders",
                "Chainable reader", // typeGroupName
                "Chainable readers",
                "Chainable reader \"&lt;{0}&gt;\"",
                "<code>&lt;{0}&gt;</code>"
            )
        );
        antTypeGroups.put(
            AntDoclet.rootDoc.classNamed("org.apache.tools.ant.taskdefs.condition.Condition"),
            new AntTypeGroup(
                "conditions",
                "Condition", // typeGroupName
                "Conditions",
                "Condition \"&lt;{0}&gt;\"",
                "<code>&lt;{0}&gt;</code>"
            )
        );
        antTypeGroups.put(null, new AntTypeGroup(
            "otherTypes",
            "Other type", // typeGroupName
            "Other types",
            "Type \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));

        for (String antlibResource : AntDoclet.antlibResources) {
            try {
                AntDoclet.parseAntlibResource(antlibResource, antTypeGroups);
            } catch (Longjump l) {}
        }

        for (File antlibFile : AntDoclet.antlibFiles) {
            AntDoclet.parseAntlibFile(antlibFile, antTypeGroups);
        }

        // Now render the type documentation pages, e.g. "tasks/myTask.html"
        for (final AntTypeGroup typeGroup : antTypeGroups.values()) {

            for (final ElementWithContext<AntType> atwc : IterableUtil.iterableWithContext(typeGroup.types)) {
                AntType antType = atwc.current();

                // Copy any "pkg/doc-files/" tree to "typegroup/doc-files/".
                URL docFilesLocation = IoUtil.findOnPath(
                    AntDoclet.sourcePath,
                    antType.classDoc.containingPackage().name().replace('.', '/') + "/doc-files"
                );
                if (docFilesLocation != null) {
                    File destinationFolder = new File(AntDoclet.options.destination, typeGroup.subdir + "/doc-files");
                    IoUtil.createMissingParentDirectoriesFor(destinationFolder);
                    IoUtil.copyTree(docFilesLocation, destinationFolder, CollisionStrategy.IO_EXCEPTION_IF_DIFFERENT);
                }

                // Because the HTML page hierarchy and the fragment identifier names are different from the standard
                // JAVADOC structure, we must have a custom link maker.
                final Html html = new Html(new Html.ExternalJavadocsLinkMaker(
                    AntDoclet.externalJavadocs,
                    AntDoclet.linkMaker(antType, typeGroup, antTypeGroups, AntDoclet.rootDoc)
                ));

                NoTemplate.render(
                    TypeHtml.class,
                    new File(AntDoclet.options.destination, typeGroup.subdir + '/' + antType.name + ".html"),
                    new ConsumerWhichThrows<TypeHtml, RuntimeException>() {

                        @Override public void
                        consume(TypeHtml typeHtml) throws RuntimeException {
                            typeHtml.render(
                                typeGroup,
                                antTypeGroups.values(),
                                atwc,
                                html,
                                AntDoclet.rootDoc,
                                AntDoclet.options
                            );
                        }
                    },
                    true,
                    AntDoclet.options.documentCharset,
                    AntDoclet.options.quiet
                );
            }
        }

        final LinkMaker linkMaker = AntDoclet.linkMaker(null, null, antTypeGroups, AntDoclet.rootDoc);
        final Html      html      = new Html(new Html.ExternalJavadocsLinkMaker(AntDoclet.externalJavadocs, linkMaker));

        // Generate the document that is loaded into the "left frame" and displays all types in groups.
        NoTemplate.render(
            AllDefinitionsHtml.class,
            new File(AntDoclet.options.destination, "alldefinitions-frame.html"),
            (AllDefinitionsHtml allDefinitionsHtml) -> {
                allDefinitionsHtml.render(
                    antTypeGroups.values(), // antTypeGroups
                    AntDoclet.rootDoc,      // rootDoc
                    AntDoclet.options,      // options
                    html,                   // html
                    "classFrame"            // target
                );
            },
            true,
            AntDoclet.options.documentCharset,
            AntDoclet.options.quiet
        );

        // Generate the "All definitions" document that is used only in the no-frame variant.
        NoTemplate.render(
            AllDefinitionsHtml.class,
            new File(AntDoclet.options.destination, "alldefinitions-noframe.html"),
            (AllDefinitionsHtml allDefinitionsHtml) -> {
                allDefinitionsHtml.render(
                    antTypeGroups.values(), // antTypeGroups
                    AntDoclet.rootDoc,      // rootDoc
                    AntDoclet.options,      // options
                    html,                   // html
                    null                    // target
                );
            },
            true,
            AntDoclet.options.documentCharset,
            AntDoclet.options.quiet
        );

        // Generate "overview-summary.html" - the document that is initially loaded into the "right frame" and displays
        // all type summaries (type name and first sentence of description).
        NoTemplate.render(
            OverviewSummaryHtml.class,
            new File(AntDoclet.options.destination, "overview-summary.html"),
            (OverviewSummaryHtml overviewSummaryHtml) -> {
                overviewSummaryHtml.render(
                    antTypeGroups.values(),
                    AntDoclet.rootDoc,
                    AntDoclet.options,
                    html
                );
            },
            true,
            AntDoclet.options.documentCharset,
            AntDoclet.options.quiet
        );

        // "Render" an empty "package-list" file. That comes in handy in envorinments where you cannot control the
        // "-link" and/or "-linkoffline" links that are passed to JAVADOC, e.g. the JAVADOC MAVEN plug-in.
        IoUtil.outputFile(new File(AntDoclet.options.destination, "package-list"), (File f) -> f.createNewFile(), true);

        return true;
    }

    private static File[]
    parsePath(String s) {

        String[] sa = s.split(Pattern.quote(File.pathSeparator));

        File[] result = new File[sa.length];
        for (int i = 0; i < sa.length; i++) result[i] = new File(sa[i]);

        return result;
    }

    /**
     * Parses the {@code <taskdef>}s, {@code <typedef>}s and {@code <componentdef>}s from the
     * <var>antlibResource</var>, which is looked up along the <var>sourcePath</var>.
     *
     * <p>
     *   The <var>antlibResource</var> may contain references to nested ANTLIB files and resources.
     * </p>
     * @param result Parsed ANT types are added to this map
     */
    private static void
    parseAntlibResource(
        String                            antlibResourceName,
        final Map<ClassDoc, AntTypeGroup> result
    ) throws Exception, Longjump {

        URL antlibLocation = IoUtil.findOnPath(AntDoclet.sourcePath, antlibResourceName);
        if (antlibLocation == null) {
            antlibLocation = IoUtil.findOnPath(AntDoclet.classPath, antlibResourceName);
            if (antlibLocation == null) {
                AntDoclet.rootDoc.printError(
                    "Antlib resource \""
                    + antlibResourceName
                    + "\" not found on the source path ("
                    + Arrays.toString(AntDoclet.sourcePath)
                    + ") nor on the class path ("
                    + Arrays.toString(AntDoclet.classPath)
                    + ")"
                );
                throw new Longjump();
            }
        }

        AntDoclet.parseAntlibStream(
            antlibLocation.openStream(), // antlibStream
            antlibLocation.toString(),   // publicId
            result                       // result
        );
    }

    /**
     * Parses the {@code <taskdef>}s, {@code <typedef>}s and {@code <componentdef>}s from the <var>antlibFile</var>,
     * and stores the result in the <var>result</var> map.
     * <p>
     *   The <var>antlibFile</var> may contain references to nested ANTLIB files and resources.
     * </p>
     * @param result Parsed ANT types are added to this map
     */
    private static void
    parseAntlibFile(File antlibFile, final Map<ClassDoc, AntTypeGroup> result) throws Exception {

        Document document = XmlUtil.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder(), antlibFile, null);
        AntDoclet.parseAntlibDocument(document, result);
    }

    /**
     * Parses the {@code <taskdef>}s, {@code <typedef>}s and {@code <componentdef>}s from the <var>antlibFile</var>,
     * and stores the result in the <var>result</var> map.
     * <p>
     *   The <var>antlibFile</var> may contain references to nested ANTLIB files and resources.
     * </p>
     * @param result Parsed ANT types are added to this map
     */
    private static void
    parseAntlibStream(
        InputStream                       antlibStream,
        @Nullable String                  publicId,
        final Map<ClassDoc, AntTypeGroup> result
    ) throws Exception {

        InputSource inputSource = new InputSource(antlibStream);
        inputSource.setPublicId(publicId);

        Document document = XmlUtil.parse(DocumentBuilderFactory.newInstance().newDocumentBuilder(), inputSource);

        AntDoclet.parseAntlibDocument(document, result);
    }

    private static void
    parseAntlibDocument(Document document, final Map<ClassDoc, AntTypeGroup> result) throws Exception {

        document.getDocumentElement().normalize();

        // Now parse the contents of the given ANTLIB file; see
        // https://ant.apache.org/manual/Types/antlib.html
        for (Element typedefElement : IterableUtil.concat(
            AntDoclet.<Element>nl2i(document.getElementsByTagName("taskdef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("typedef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("componentdef"))
        )) {
            try {
                AntDoclet.parseTypedef(
                    typedefElement,
                    AntDoclet.sourcePath,
                    AntDoclet.classPath,
                    AntDoclet.rootDoc,
                    result
                );
            } catch (Longjump l) {
                continue;
            }
        }

        if (document.getElementsByTagName("macrodef").getLength() > 0) {
            AntDoclet.rootDoc.printWarning("<macrodef>s are not yet supported");
        }
        if (document.getElementsByTagName("presetdef").getLength() > 0) {
            AntDoclet.rootDoc.printWarning("<presetdef>s are not yet supported");
        }
        if (document.getElementsByTagName("scriptdef").getLength() > 0) {
            AntDoclet.rootDoc.printWarning("<scriptdef>s are not yet supported");
        }
    }

    /**
     * Parses an element in an ANTLIB file (typically a {@code <taskdef>}, {@code <typedef>} or {@code <componentdef>}
     * element) and adds the resulting ANT type(s) to <var>result</var>.
     * <p>
     *   The <var>antlibFile</var> may contain references to nested ANTLIB files, which are looked up along the
     *   <var>sourcePath</var>.
     * </p>
     */
    private static void
    parseTypedef(
        Element                     typedefElement,
        @Nullable File[]            sourcePath,
        @Nullable File[]            classPath,
        RootDoc                     rootDoc,
        Map<ClassDoc, AntTypeGroup> result
    ) throws Exception, Longjump {

        final String nameAttribute      = AntDoclet.getOptionalAttribute(typedefElement, "name");
        final String classnameAttribute = AntDoclet.getOptionalAttribute(typedefElement, "classname");
        final String adaptToAttribute   = AntDoclet.getOptionalAttribute(typedefElement, "adaptTo");
        final String resourceAttribute  = AntDoclet.getOptionalAttribute(typedefElement, "resource");
        final String fileAttribute      = AntDoclet.getOptionalAttribute(typedefElement, "file");

        if (
            nameAttribute         == null
            && classnameAttribute == null
            && adaptToAttribute   == null
            && resourceAttribute  != null
            && fileAttribute      == null
        ) {

            // <typedef resource="path/to/antlib.xml" />
            AntDoclet.parseAntlibResource(resourceAttribute, result);
        } else
        if (
            nameAttribute         == null
            && classnameAttribute == null
            && adaptToAttribute   == null
            && resourceAttribute  == null
            && fileAttribute      != null
        ) {

            // <typedef file="path/to/antlib.xml" />
            AntDoclet.parseAntlibFile(new File(fileAttribute), result);
        } else
        if (
            nameAttribute         != null
            && classnameAttribute != null
            && resourceAttribute  == null
            && fileAttribute      == null
        ) {

            // <typedef name="echo" classname="com.acme.ant.EchoTask" />
            final ClassDoc classDoc = rootDoc.classNamed(classnameAttribute);
            if (classDoc == null) {
                rootDoc.printError("Class '" + classnameAttribute + "' not found for  <" + nameAttribute + ">");
                throw new Longjump();
            }

            final ClassDoc adaptTo;
            if (adaptToAttribute == null) {
                adaptTo = null;
            } else {
                adaptTo = rootDoc.classNamed(adaptToAttribute);
                if (adaptTo == null) {
                    rootDoc.printError("Class '" + adaptToAttribute + "' not found for <" + nameAttribute + ">");
                    throw new Longjump();
                }
            }

            // Deduce attributes and subelements from the special methods that ANT uses.
            AntType antType = new AntType(
                nameAttribute,
                classDoc,
                adaptTo,
                AntDoclet.characterDataOf(classDoc),
                AntDoclet.attributesOf(classDoc, rootDoc),
                AntDoclet.subelementsOf(classDoc, rootDoc)
            );

            if (typedefElement.getTagName().equals("taskdef")) {
                result.get(rootDoc.classNamed("org.apache.tools.ant.Task")).types.add(antType);
            } else {

                boolean hadOneTypeGroup = false;
                for (ClassDoc cd : Docs.withSuperclassesAndInterfaces(antType.classDoc)) {

                    AntTypeGroup atg = result.get(cd);
                    if (atg == null) {

                        Tag typeGroupSubdirTag = Tags.optionalTag(cd, "@ant.typeGroupSubdir",  rootDoc);
                        if (typeGroupSubdirTag == null) continue;

                        // SUPPRESS CHECKSTYLE LineLength:5
                        String typeGroupSubdir  = typeGroupSubdirTag.text();
                        String typeGroupName    = Tags.requiredTag(cd, "@ant.typeGroupName",    rootDoc).text();
                        String typeGroupHeading = Tags.requiredTag(cd, "@ant.typeGroupHeading", rootDoc).text();
                        String typeTitleMf      = Tags.requiredTag(cd, "@ant.typeTitleMf",      rootDoc).text();
                        String typeHeadingMf    = Tags.requiredTag(cd, "@ant.typeHeadingMf",    rootDoc).text();

                        result.put(cd, (atg = new AntTypeGroup(
                            typeGroupSubdir,  // subdir
                            typeGroupName,    // name
                            typeGroupHeading, // heading
                            typeTitleMf,      // typeTitleMf
                            typeHeadingMf     // typeHeadingMf
                        )));
                    }

                    atg.types.add(antType);
                    hadOneTypeGroup = true;
                }

                if (!hadOneTypeGroup) result.get(null).types.add(antType);
            }
        } else
        {
            rootDoc.printError("Invalid combination of attributes");
            throw new Longjump();
        }
    }

    @Nullable private static String
    getOptionalAttribute(Element element, String attributeName) {
        String result = element.getAttribute(attributeName);
        return result.length() == 0 ? null : result;
    }

    private static LinkMaker
    linkMaker(
        @Nullable final AntType           antType,
        @Nullable final AntTypeGroup      typeGroup,
        final Map<ClassDoc, AntTypeGroup> antTypeGroups,
        RootDoc                           rootDoc
    ) {

        return new LinkMaker() {

            @Override public Link
            makeLink(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

                if (to instanceof ClassDoc) {
                    ClassDoc toClass = (ClassDoc) to;

                    // Link to an ANT type in this ANTLIB?
                    for (AntTypeGroup atg : antTypeGroups.values()) {
                        for (AntType t : atg.types) {
                            if (toClass == t.classDoc) {
                                return new Link(
                                    (
                                        antType == null ? atg.subdir + '/' + t.name + ".html" :
                                        atg == typeGroup ? t.name + ".html" :
                                        "../" + atg.subdir + '/' + t.name + ".html"
                                    ),
                                    "&lt;" + t.name + "&gt;"
                                );
                            }
                        }
                    }

                    // Link to external ANT task/type documentation?
                    {
                        Link link = AntDoclet.externalAntdocs.get(to);
                        if (link != null) {
                            return new Link(link.href, StringUtil.firstLetterToUpperCase(link.defaultLabelHtml));
                        }
                    }

                    // Link to a (publicly declared) subelement of this ANT type?
                    if (antType != null) {
                        for (AntSubelement se : antType.subelements) {
                            if (toClass == se.type) {
                                if (se.name != null) {
                                    return new Link(
                                        "#" + toClass.qualifiedName() + "_subelement_detail",
                                        "<code>&lt;" + se.name + "&gt;</code>"
                                    );
                                }

                                AntTypeGroup atg = antTypeGroups.get(toClass);
                                if (atg != null) {
                                    return new Link(
                                        "../overview-summary.html#" + atg.subdir,
                                        "Any " + atg.name
                                    );
                                }
                            }
                        }
                    }

                    // Link to an interface that represents a "type group", e.g. "resource collection"?
                    {
                        AntTypeGroup atg = antTypeGroups.get(toClass);
                        if (atg != null) {
                            return new Link(
                                "../overview-summary.html#" + atg.subdir,
                                "Any " + atg.name
                            );
                        }
                    }

                    rootDoc.printError(from.position(), "'" + to + "' does not designate a type");
                    throw new Longjump();
                }

                if (to instanceof MethodDoc) {
                    MethodDoc toMethod = (MethodDoc) to;
                    ClassDoc  toClass  = toMethod.containingClass();

                    // Link to an attribute of this ANT type?
                    if (antType != null) {
                        for (AntAttribute a : antType.attributes) {
                            if (a.methodDoc == toMethod) {
                                return new Link('#' + a.name + "_attribute_detail", a.name + "=\"...\"");
                            }
                        }
                    }

                    for (AntTypeGroup tg : antTypeGroups.values()) {
                        for (AntType t : tg.types) {

//                            if (t.classDoc != toClass) continue;

                            // Link to the "addText()" method (of the same or a different ANT type)?
                            if (toMethod == t.characterData) {
                                String fragment = "#text_summary";
                                return new Link(
                                    (
                                        antType == null ? tg.subdir + '/' + t.name + fragment :
                                        toClass == antType.classDoc ? fragment :
                                        typeGroup == tg ? t.name + fragment :
                                        "../" + tg.subdir + '/' + t.name + fragment
                                    ),
                                    "(text between start and end tag)"
                                );
                            }

                            // Link to an attribute (of the same or a different ANT type)?
                            for (AntAttribute a : t.attributes) {
                                if (a.methodDoc == toMethod) {
                                    String fragment = '#' + a.name + "_attribute_detail";
                                    return new Link(
                                        (
                                            antType == null ? tg.subdir + '/' + t.name + ".html" + fragment :
                                            toClass == antType.classDoc ? fragment :
                                            typeGroup == tg ? t.name + ".html" + fragment :
                                            "../" + tg.subdir + '/' + t.name + ".html" + fragment
                                        ),
                                        a.name + "=\"...\""
                                    );
                                }
                            }

                            // Link to an ad-hoc subelement (of the same or a different ANT type)?
                            for (AntSubelement se : t.subelements) {
                                if (se.methodDoc == toMethod) {
                                    String fragment = (
                                        '#'
                                        + (se.name != null ? se.name : se.type.asClassDoc().qualifiedName())
                                        + "_subelement_detail"
                                    );
                                    String label = se.name;
                                    if (label != null) {
                                        label = "&lt;" + se.name + "&gt;";
                                    } else {
                                        AntTypeGroup atg = antTypeGroups.get(se.type.asClassDoc());
                                        label = (
                                            atg != null
                                            ? "Any " + atg.name
                                            : "<code>" + se.type.asClassDoc().qualifiedName() + "</code>"
                                        );
                                    }
                                    return new Link(
                                        (               // href
                                            antType == null ? tg.subdir + '/' + t.name + ".html" + fragment :
                                            toMethod.containingClass() == antType.classDoc ? fragment :
                                            typeGroup == tg ? antType.name + ".html" + fragment :
                                            "../" + tg.subdir + '/' + antType.name + ".html" + fragment
                                        ),
                                        label           // defaultLabelHtml
                                    );
                                }
                            }

                            // Link to an attribute of a subelement (of the same or a different ANT type)?
                            for (AntSubelement se : t.subelements) {
                                Parameter[] params = se.methodDoc.parameters();
                                if (params.length != 1) continue;
                                for (MethodDoc md : Docs.methods(params[0].type().asClassDoc(), false, true)) {
                                    if (md == toMethod) {
                                        String labelHtml = md.name();
                                        {
                                            Matcher m;
                                            if ((m = AntDoclet.SET_ATTRIBUTE_METHOD_NAME.matcher(labelHtml)).matches()) { // SUPPRESS CHECKSTYLE LineLength
                                                labelHtml = (
                                                    Notations
                                                    .fromCamelCase(m.group("attributeName"))
                                                    .toLowerCamelCase()
                                                ) + "=\"...\"";
                                            } else
                                            if (
                                                (m = AntDoclet.ADD_SUBELEMENT_METHOD_NAME.matcher(labelHtml)).matches()
                                                || (m = AntDoclet.CREATE_SUBELEMENT_METHOD_NAME.matcher(labelHtml)).matches() // SUPPRESS CHECKSTYLE LineLength
                                            ) {
                                                labelHtml = "&lt;" + (
                                                    Notations
                                                    .fromCamelCase(m.group("subelementName"))
                                                    .toLowerCamelCase()
                                                ) + "&gt;";
                                            }
                                        }
                                        String fragment = '#' + md.containingClass().qualifiedName() + "/" + md.name();
                                        return new Link(
                                            (
                                                antType == null ? tg.subdir + '/' + t.name + ".html" + fragment :
                                                toClass == antType.classDoc ? fragment :
                                                typeGroup == tg ? t.name + ".html" + fragment :
                                                "../" + tg.subdir + '/' + t.name + ".html" + fragment
                                            ),
                                            labelHtml
                                        );
                                    }
                                }
                            }
                        }
                    }

                    rootDoc.printWarning(from.position(), (
                        "Linking from '"
                        + from
                        + "' to '"
                        + to
                        + "': '"
                        + toMethod
                        + "' is not an attribute setter nor a subelement adder/creator"
                    ));
                    return new Link(null, to.name());
                }

                if (to instanceof RootDoc) {
                    // {@ rootDoc} tag.
                    return new Link("..", "???");
                }

                return new Link(null, to.name());
            }
        };
    }

    /**@return The given <var>nodeList</var>, wrapped as an {@link Iterable} */
    private static <N extends Node> Iterable<N>
    nl2i(final NodeList nodeList) {

        return new Iterable<N>() {

            @Override public Iterator<N>
            iterator() {

                return new Iterator<N>() {

                    int idx;

                    @Override public boolean
                    hasNext() { return this.idx < nodeList.getLength(); }

                    @Override public N
                    next() {

                        if (this.idx >= nodeList.getLength()) throw new NoSuchElementException();

                        @SuppressWarnings("unchecked") N result = (N) nodeList.item(this.idx++);
                        return result;
                    }

                    @Override public void
                    remove() { throw new UnsupportedOperationException("remove"); }
                };
            }
        };
    }

    /**
     * @return The {@link MethodDoc} of the "{@code addText(String)}", or {@code null}
     */
    @Nullable public static MethodDoc
    characterDataOf(final ClassDoc classDoc) {

        for (MethodDoc md : Docs.methods(classDoc, true, true)) {

            String      methodName       = md.name();
            Parameter[] methodParameters = md.parameters();

            if (
                AntDoclet.ADD_TEXT_METHOD_NAME.matcher(methodName).matches()
                && methodParameters.length == 1
                && "java.lang.String".equals(methodParameters[0].type().qualifiedTypeName())
            ) return md;
        }

        return null;
    }

    public static List<AntAttribute>
    attributesOf(final ClassDoc classDoc, RootDoc rootDoc) {

        final List<AntAttribute>  attributes  = new ArrayList<>();
        METHODS:
        for (MethodDoc md : Docs.methods(classDoc, true, true)) {

            if (!md.isPublic() || "setProject".equals(md.name())) continue;

            String      methodName       = md.name();
            Parameter[] methodParameters = md.parameters();

            Matcher m;
            if (
                (m = AntDoclet.SET_ATTRIBUTE_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 1
            ) {
                String name = Notations.fromCamelCase(m.group("attributeName")).toLowerCamelCase();
                Type   type = methodParameters[0].type();

                for (Iterator<AntAttribute> it = attributes.iterator(); it.hasNext();) {
                    AntAttribute a = it.next();
                    if (a.name.equals(name) && a.type == type) {

                        // "md" is an overridden method; "a.methodDoc" is the overriding method.
                        if (md.inlineTags().length > 0 && a.methodDoc.inlineTags().length == 0) {
                            it.remove();
                            break;
                        } else {
                            continue METHODS;
                        }
                    }
                }
                Tag groupTag = Tags.optionalTag(md, "@ant.group", rootDoc);
                attributes.add(new AntAttribute(name, md, type, groupTag == null ? null : groupTag.text()));
            }
        }
        return attributes;
    }

    public static List<AntSubelement>
    subelementsOf(final ClassDoc classDoc, RootDoc rootDoc) {

        final List<AntSubelement> subelements = new ArrayList<>();
        METHODS: for (MethodDoc md : Docs.methods(classDoc, true, true)) {

            String      methodName       = md.name();
            Parameter[] methodParameters = md.parameters();

            Matcher m;
            String  name;
            Type    type;
            if (
                (m = AntDoclet.CREATE_SUBELEMENT_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 0
            ) {
                name = Notations.fromCamelCase(m.group("subelementName")).toLowerCamelCase();
                type = md.returnType();
            } else
            if (
                (m = AntDoclet.ADD_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 1
            ) {
                name = null;
                type = methodParameters[0].type();
            } else
            if (
                (m = AntDoclet.ADD_SUBELEMENT_METHOD_NAME.matcher(methodName)).matches()
                && !AntDoclet.ADD_TEXT_METHOD_NAME.matcher(methodName).matches()
                && methodParameters.length == 1
            ) {
                name = Notations.fromCamelCase(m.group("subelementName")).toLowerCamelCase();
                type = methodParameters[0].type();
            } else
            {
                continue;
            }

            for (Iterator<AntSubelement> it = subelements.iterator(); it.hasNext();) {
                AntSubelement as = it.next();

                // Was a method for the same subelement documented before?
                if (ObjectUtil.equals(as.name, name) && as.type == type) {

                    if (md.tags().length == 0) {

                        // Yes, but the "new" method has no documentation, so we leave the "old" method.
                        continue METHODS;
                    }

                    if (as.methodDoc.inlineTags().length == 0) {

                        // Yes, and the "old" method has no documentation, so we replace it with the "new" method.
                        it.remove();
                        break;
                    }
                }
            }

            Tag groupTag = Tags.optionalTag(md, "@ant.group", rootDoc);
            subelements.add(new AntSubelement(md, name, type, groupTag == null ? null : groupTag.text()));
        }

        Tag sot = Tags.optionalTag(classDoc, "@ant.subelementOrder", rootDoc);
        if (sot != null && "inheritedFirst".equals(sot.text())) {
            List<AntSubelement> tmp1 = new ArrayList<>();
            List<AntSubelement> tmp2 = new ArrayList<>();
            for (AntSubelement se : subelements) {
                if (se.methodDoc.containingClass() == classDoc) {
                    tmp1.add(se);
                } else {
                    tmp2.add(se);
                }
            }
            subelements.clear();
            subelements.addAll(tmp2);
            subelements.addAll(tmp1);
        }

        return subelements;
    }
}
