
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
 *    3. The name of the author may not be used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package de.unkrig.doclet.ant;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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
 * <p>
 *   Opens, reads and parses an <a href="http://ant.apache.org/manual/Types/antlib.html">ANTLIB</a> file and generates
 *   one document per type, task, macro, preset and script defined therein.
 * </p>
 * <p>
 *   Supports the following command line options:
 * </p>
 * <dl>
 *   <dt>{@code -antlib-file} <var>file</var></dt>
 *   <dd>Source of task names an class names (mandatory).</dd>
 *   <dt>{@code -html-output-directory} <var>dir</var></dt>
 *   <dd>Where to create documentation in HTML format (optional).</dd>
 *   <dt>{@code -mediawiki-output-directory} <var>dir</var></dt>
 *   <dd>Where to create documentation in MEDIAWIKI markup format (optional).</dd>
 *   <dt>{@code -link <var>target-url</var></dt>
 *   <dt>{@code -linkoffline} <var>target-url</var> <var>package-list-url</var></dt>
 *   <dd>See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDEDJFI">here</a>.</dd>
 *   <dt>{@code -doctitle} <var>text</var></dt>
 *   <dd>See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDJGBIE">here</a>.</dd>
 *   <dt>{@code -windowtitle} <var>text</var></dt>
 *   <dd>See <a href="http://docs.oracle.com/javase/8/docs/technotes/tools/windows/javadoc.html#CHDBIEEI">here</a>.</dd>
 *   <dt>{@code -theme JAVA7|JAVA8}</dt>
 *   <dd>Which style sheets and resources to use.</dd>
 * </dl>
 */
public
class AntDoclet {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    private static final Pattern ADD_TEXT_METHOD_NAME          = Pattern.compile("addText");
    private static final Pattern SET_ATTRIBUTE_METHOD_NAME     = Pattern.compile("set(?<attributeName>[A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME               = Pattern.compile("add(?:Configured)?");
    private static final Pattern ADD_SUBELEMENT_METHOD_NAME    = Pattern.compile("add(?:Configured)?(?<subelementName>[A-Z]\\w*)"); // SUPPRESS CHECKSTYLE LineLength
    private static final Pattern CREATE_SUBELEMENT_METHOD_NAME = Pattern.compile("create(?<subelementName>[A-Z]\\w*)");

    private enum Theme { JAVA7, JAVA8 }

    private RootDoc                                rootDoc;
    private final Options                          options;
    private final File                             antlibFile;
    private final Map<String /*packageName*/, URL> externalJavadocs;
    private final Theme                            theme;
    private final String[]                         sourcePath;

    /**
     * Documentation URLs for well-known ANT tasks and types.
     */
    private final Mapping<ClassDoc, Link> externalAntdocs;

    public
    AntDoclet(
        RootDoc            rootDoc,
        Options            options,
        File               antlibFile,
        Map<String, URL>   externalJavadocs,
        Theme              theme,
        @Nullable String[] sourcePath
    ) throws IOException {

        this.rootDoc          = rootDoc;
        this.options          = options;
        this.antlibFile       = antlibFile;
        this.externalJavadocs = externalJavadocs;
        this.theme            = theme;
        this.sourcePath       = sourcePath;

        Map<ClassDoc, Link> m = new HashMap<ClassDoc, Link>();

        // Resource "de/unkrig/doclet/ant/AntDoclet/external-antdocs.properties" provides a number of
        // qualified-class-name to label/href mappings.
        {
            Properties properties;
            try {
                properties = this.loadPropertiesFromResource((
                    this.getClass().getPackage().getName().replace('.', '/')
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
            try {
                properties = this.loadPropertiesFromResource("org/apache/tools/ant/taskdefs/defaults.properties");
            } catch (IOException ioe) {
                throw ExceptionUtil.wrap("Make sure that \"ant.jar\" is on the doclet's classpath", ioe);
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
                properties = this.loadPropertiesFromResource("org/apache/tools/ant/types/defaults.properties");
            } catch (IOException ioe) {
                throw ExceptionUtil.wrap("Make sure that \"ant.jar\" is on the doclet's classpath", ioe);
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

        this.externalAntdocs = Mappings.fromMap(m);
    }

    private Properties
    loadPropertiesFromResource(String resourceName) throws IOException {

        InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourceName);
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
        @Nullable
        public final String group;

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
    optionLength(String option) {

        // Options that go into the "Options" object:
        if ("-d".equals(option))           return 2;
        if ("-windowtitle".equals(option)) return 2;
        if ("-doctitle".equals(option))    return 2;
        if ("-header".equals(option))      return 2;
        if ("-footer".equals(option))      return 2;
        if ("-top".equals(option))         return 2;
        if ("-bottom".equals(option))      return 2;
        if ("-notimestamp".equals(option)) return 1;

        // "Other" options:
        if ("-antlib-file".equals(option)) return 2;
        if ("-link".equals(option))        return 2;
        if ("-linkoffline".equals(option)) return 3;
        if ("-theme".equals(option))       return 2;

        return 0;
    }

    /**
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static boolean
    start(final RootDoc rootDoc) throws IOException, ParserConfigurationException, SAXException {

        File                                              antlibFile       = new File("antlib.xml");
        final Map<String /*packageName*/, URL /*target*/> externalJavadocs = new HashMap<String, URL>();
        Theme                                             theme            = Theme.JAVA8;
        String[]                                          sourcePath       = null;

        Options options   = new Options();
        options.generator = "the ANT doclet http://doclet.unkrig.de";

        for (String[] option : rootDoc.options()) {

            // Options that go into the "Options" object:
            if ("-d".equals(option[0])) {
                options.destination = new File(option[1]);
            } else
            if ("-windowtitle".equals(option[0])) {
                options.windowTitle = option[1];
            } else
            if ("-doctitle".equals(option[0])) {
                options.docTitle = option[1];
            } else
            if ("-header".equals(option[0])) {
                options.header = option[1];
            } else
            if ("-footer".equals(option[0])) {
                options.footer = option[1];
            } else
            if ("-top".equals(option[0])) {
                options.top = option[1];
            } else
            if ("-bottom".equals(option[0])) {
                options.bottom = option[1];
            } else
            if ("-notimestamp".equals(option[0])) {
                options.noTimestamp = Boolean.parseBoolean(option[1]);
            } else

            // "Other" options.
            if ("-antlib-file".equals(option[0])) {
                antlibFile = new File(option[1]);
            } else
            if ("-link".equals(option[0])) {

                String externalDocumentationUrl = option[1];

                if (!externalDocumentationUrl.endsWith("/")) externalDocumentationUrl += "/";

                URL externalDocumentationUrl2 = new URL(
                    new URL("file", null, -1, options.destination.toString()),
                    externalDocumentationUrl
                );

                Docs.readExternalJavadocs(
                    externalDocumentationUrl2, // targetUrl
                    externalDocumentationUrl2, // packageListUrl
                    externalJavadocs,          // externalJavadocs
                    rootDoc
                );
            } else
            if ("-linkoffline".equals(option[0])) {

                String externalDocumentationUrl = option[1];
                String packageListLocation      = option[2];

                if (!externalDocumentationUrl.endsWith("/")) externalDocumentationUrl += "/";
                if (!packageListLocation.endsWith("/"))      packageListLocation      += "/";

                URL externalDocumentationUrl2 = new URL(
                    new URL("file", null, -1, options.destination.toString()),
                    externalDocumentationUrl
                );

                URL packageListUrl = (
                    packageListLocation.startsWith("http.") || packageListLocation.startsWith("file:")
                    ? new URL(new URL("file", null, -1, System.getProperty("user.dir")), packageListLocation)
                    : new URL("file", null, -1, packageListLocation)
                );

                Docs.readExternalJavadocs(
                    externalDocumentationUrl2, // targetUrl
                    packageListUrl,            // packageListUrl
                    externalJavadocs,          // externalJavadocs
                    rootDoc
                );
            } else
            if ("-theme".equals(option[0])) {
                theme = Theme.valueOf(option[1]);
            } else
            if ("-sourcepath".equals(option[0]) && option.length == 2) {
                sourcePath = option[1].split(Pattern.quote(File.pathSeparator));
            } else
            {

                // It is quite counterintuitive, but 'options()' returns ALL options, not only those which
                // qualified by 'optionLength()'.
                ;
            }
        }

        new AntDoclet(rootDoc, options, antlibFile, externalJavadocs, theme, sourcePath).start2();

        return true;
    }

    /**
     * Generates the ANT documentation for this configured object.
     */
    void
    start2() throws IOException, ParserConfigurationException, SAXException {

        switch (this.theme) {

        case JAVA7:
            String resourceNamePrefix = "de/unkrig/doclet/ant/theme/java7/";
            for (String resourceNameSuffix : new String[] {
                "stylesheet.css",
                "resources/background.gif",
                "resources/tab.gif",
                "resources/titlebar_end.gif",
                "resources/titlebar.gif",
            }) {
                File file = new File(this.options.destination, resourceNameSuffix);
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
                new File(this.options.destination, "stylesheet.css"),
                true // createMissingParentDirectories
            );
            break;
        }

        IoUtil.copyResource(
            AntDoclet.class.getClassLoader(),
            "de/unkrig/doclet/ant/templates/stylesheet2.css",
            new File(this.options.destination, "stylesheet2.css"),
            true // createMissingParentDirectories
        );

        // Render "index.html" (the frameset).
        NoTemplate.render(
            IndexHtml.class,
            new File(this.options.destination, "index.html"),
            (IndexHtml indexHtml) -> { indexHtml.render(AntDoclet.this.options); }
        );

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder        documentBuilder        = documentBuilderFactory.newDocumentBuilder();
        Document               document               = documentBuilder.parse(this.antlibFile);

        document.getDocumentElement().normalize();

        final LinkedHashMap<ClassDoc, AntTypeGroup> antTypeGroups = new LinkedHashMap<>();
        AntTypeGroup                                antTypeGroupTasks, antTypeGroupOther;
        antTypeGroups.put(this.rootDoc.classNamed("org.apache.tools.ant.Task"), (antTypeGroupTasks = new AntTypeGroup(
            "tasks",
            "Task", // typeGroupName
            "Tasks",
            "Task \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        )));
        antTypeGroups.put(this.rootDoc.classNamed("org.apache.tools.ant.types.ResourceCollection"), new AntTypeGroup(
            "resourceCollections",
            "Resource collection", // typeGroupName
            "Resource collections",
            "Resource collection \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.put(this.rootDoc.classNamed("org.apache.tools.ant.filters.ChainableReader"), new AntTypeGroup(
            "chainableReaders",
            "Chainable reader", // typeGroupName
            "Chainable readers",
            "Chainable reader \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.put(
            this.rootDoc.classNamed("org.apache.tools.ant.taskdefs.condition.Condition"),
            new AntTypeGroup(
                "conditions",
                "Condition", // typeGroupName
                "Conditions",
                "Condition \"&lt;{0}&gt;\"",
                "<code>&lt;{0}&gt;</code>"
            )
        );
        antTypeGroups.put(null, (antTypeGroupOther = new AntTypeGroup(
            "otherTypes",
            "Other type", // typeGroupName
            "Other types",
            "Type \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        )));

        // Now parse the contents of the given ANTLIB file; see
        // https://ant.apache.org/manual/Types/antlib.html
        for (Element taskdefElement : AntDoclet.<Element>nl2i(document.getElementsByTagName("taskdef"))) {
            AntType antType;
            try {
                antType = AntDoclet.parseType(taskdefElement, this.rootDoc);
            } catch (Longjump l) {
                continue;
            }
            antTypeGroupTasks.types.add(antType);
        }
        for (Element typedefElement : IterableUtil.concat(
            AntDoclet.<Element>nl2i(document.getElementsByTagName("typedef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("componentdef"))
        )) {
            AntType antType;
            try {
                antType = AntDoclet.parseType(typedefElement, this.rootDoc);
            } catch (Longjump l) {
                continue;
            }

            boolean hadOneTypeGroup = false;
            for (ClassDoc cd : Docs.withSuperclassesAndInterfaces(antType.classDoc)) {

                AntTypeGroup atg = antTypeGroups.get(cd);
                if (atg == null) {

                    String typeGroupSubdir  = Tags.optionalTag(cd, "@ant.typeGroupSubdir",  this.rootDoc);
                    if (typeGroupSubdir == null) continue;
                    String typeGroupName    = Tags.requiredTag(cd, "@ant.typeGroupName",    this.rootDoc);
                    String typeGroupHeading = Tags.requiredTag(cd, "@ant.typeGroupHeading", this.rootDoc);
                    String typeTitleMf      = Tags.requiredTag(cd, "@ant.typeTitleMf",      this.rootDoc);
                    String typeHeadingMf    = Tags.requiredTag(cd, "@ant.typeHeadingMf",    this.rootDoc);

                    antTypeGroups.put(cd, (atg = new AntTypeGroup(
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

            if (!hadOneTypeGroup) antTypeGroupOther.types.add(antType);
        }
        if (document.getElementsByTagName("macrodef").getLength() > 0) {
            this.rootDoc.printWarning("<macrodef>s are not yet supported");
        }
        if (document.getElementsByTagName("presetdef").getLength() > 0) {
            this.rootDoc.printWarning("<presetdef>s are not yet supported");
        }
        if (document.getElementsByTagName("scriptdef").getLength() > 0) {
            this.rootDoc.printWarning("<scriptdef>s are not yet supported");
        }

        // Now render the type documentation pages, e.g. "tasks/myTask.html"
        for (final AntTypeGroup typeGroup : antTypeGroups.values()) {

            for (final ElementWithContext<AntType> atwc : IterableUtil.iterableWithContext(typeGroup.types)) {
                AntType antType = atwc.current();

                for (String sourceFolder : this.sourcePath == null ? new String[] { "." } : this.sourcePath) {

                    File docFilesFolder = new File(
                        sourceFolder
                        + '/'
                        + antType.classDoc.containingPackage().name().replace('.', '/')
                        + "/doc-files"
                    );

                    if (!docFilesFolder.exists()) continue;

                    File destinationFolder = new File(this.options.destination, typeGroup.subdir + "/doc-files");
                    IoUtil.createMissingParentDirectoriesFor(destinationFolder);

                    IoUtil.copyTree(
                        docFilesFolder,
                        destinationFolder,
                        CollisionStrategy.IO_EXCEPTION_IF_DIFFERENT
                    );
                }

                // Because the HTML page hierarchy and the fragment identifier names are different from the standard
                // JAVADOC structure, we must have a custom link maker.
                final Html html = new Html(new Html.ExternalJavadocsLinkMaker(
                    this.externalJavadocs,
                    this.linkMaker(antType, typeGroup, antTypeGroups.values(), this.rootDoc)
                ));

                NoTemplate.render(
                    TypeHtml.class,
                    new File(this.options.destination, typeGroup.subdir + '/' + antType.name + ".html"),
                    new ConsumerWhichThrows<TypeHtml, RuntimeException>() {

                        @Override public void
                        consume(TypeHtml typeHtml) throws RuntimeException {
                            typeHtml.render(
                                typeGroup,
                                antTypeGroups.values(),
                                atwc,
                                html,
                                AntDoclet.this.rootDoc,
                                AntDoclet.this.options
                            );
                        }
                    }
                );
            }
        }

        final LinkMaker linkMaker = this.linkMaker(null, null, antTypeGroups.values(), this.rootDoc);
        final Html      html      = new Html(new Html.ExternalJavadocsLinkMaker(this.externalJavadocs, linkMaker));

        // Generate the document that is loaded into the "left frame" and displays all types in groups.
        NoTemplate.render(
            AllDefinitionsHtml.class,
            new File(this.options.destination, "alldefinitions-frame.html"),
            (AllDefinitionsHtml allDefinitionsHtml) -> {
                allDefinitionsHtml.render(antTypeGroups.values(), AntDoclet.this.rootDoc, AntDoclet.this.options, html);
            }
        );

        // Generate "overview-summary.html" - the document that is initially loaded into the "right frame" and displays
        // all type summaries (type name and first sentence of description).
        NoTemplate.render(
            OverviewSummaryHtml.class,
            new File(this.options.destination, "overview-summary.html"),
            (OverviewSummaryHtml overviewSummaryHtml) -> {
                overviewSummaryHtml.render(
                    antTypeGroups.values(),
                    AntDoclet.this.rootDoc,
                    AntDoclet.this.options,
                    html
                );
            }
        );
    }

    private LinkMaker
    linkMaker(
        @Nullable final AntType        antType,
        @Nullable final AntTypeGroup   typeGroup,
        final Collection<AntTypeGroup> antTypeGroups,
        RootDoc                        rootDoc
    ) {

        return new LinkMaker() {

            @Override public Link
            makeLink(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

                if (to instanceof ClassDoc) {
                    ClassDoc toClass = (ClassDoc) to;

                    // Link to an ANT type in this ANTLIB?
                    for (AntTypeGroup atg : antTypeGroups) {
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
                        Link link = AntDoclet.this.externalAntdocs.get(to);
                        if (link != null) {
                            return new Link(link.href, StringUtil.firstLetterToUpperCase(link.defaultLabelHtml));
                        }
                    }

//                    // Link to a subelement of this ANT type?
//                    for (AntSubelement se : antType.subelements) {
//                        if (toClass == se.type) {
//                            return new Link(
//                                "#" + toClass.qualifiedName() + "_detail",
//                                "<code>&lt;" + se.name + "&gt;</code>"
//                            );
//                        }
//                    }

                    // Link to an interface that represents a "type group"?
                    {
                        Tag[] typeGroupNameTags   = toClass.tags("@ant.typeGroupName");
                        Tag[] typeGroupSubdirTags = toClass.tags("@ant.typeGroupSubdir");

                        if (typeGroupNameTags.length > 0) {
                            if (typeGroupNameTags.length != 1) {
                                rootDoc.printError(
                                    toClass.position(),
                                    "Exactly one @ant.typeGroupName tag must be given"
                                );
                                throw new Longjump();
                            }
                            if (typeGroupSubdirTags.length != 1) {
                                rootDoc.printError(toClass.position(), "@ant.typeGroupSubdir tag missing");
                                throw new Longjump();
                            }
                            return new Link(
                                "../overview-summary.html#" + typeGroupSubdirTags[0].text(),
                                "Any " + typeGroupNameTags[0].text()
                            );
                        }
                    }

                    rootDoc.printError(from.position(), "'" + to + "' does not designate a type");
                    throw new Longjump();
                }

                if (to instanceof MethodDoc) {
                    MethodDoc toMethod = (MethodDoc) to;
                    ClassDoc  toClass  = toMethod.containingClass();

                    for (AntTypeGroup tg : antTypeGroups) {
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
                                    String fragment = '#' + a.name;
                                    return new Link(
                                        (
                                            antType == null ? tg.subdir + '/' + t.name + fragment :
                                            toClass == antType.classDoc ? fragment :
                                            typeGroup == tg ? t.name + fragment :
                                            "../" + tg.subdir + '/' + t.name + fragment
                                        ),
                                        a.name + "=\"...\""
                                    );
                                }
                            }

                            // Link to a subelement (of the same or a different ANT type)?
                            for (AntSubelement se : t.subelements) {
                                if (se.methodDoc == toMethod) {
                                    String fragment = (
                                        '#'
                                        + (se.name != null ? se.name : se.type.asClassDoc().qualifiedName())
                                        + "_detail"
                                    );
                                    return new Link(
                                        (
                                            antType == null ? tg.subdir + '/' + t.name + ".html" + fragment :
                                            toMethod.containingClass() == antType.classDoc ? fragment :
                                            typeGroup == tg ? antType.name + ".html" + fragment :
                                            "../" + tg.subdir + '/' + antType.name + ".html" + fragment
                                        ),
                                        "&lt;" + se.name + "&gt;"
                                    );
                                }
                            }

                            // Link to an attribute of a subelement (of the same or a different ANT type)?
                            for (AntSubelement se : t.subelements) {
                                Parameter[] params = se.methodDoc.parameters();
                                if (params.length != 1) continue;
                                for (MethodDoc md : params[0].type().asClassDoc().methods()) {
                                    if (md == toMethod) {
                                        String fragment = '#' + md.containingClass().qualifiedName() + "/" + md.name();
                                        return new Link(
                                            (
                                                antType == null ? tg.subdir + '/' + t.name + ".html" + fragment :
                                                toClass == antType.classDoc ? fragment :
                                                typeGroup == tg ? t.name + ".html" + fragment :
                                                "../" + tg.subdir + '/' + t.name + ".html" + fragment
                                            ),
                                            md.name() + "=\"...\""
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

                return new Link(null, to.name());
            }
        };
    }

    private static boolean
    typeIs(AntType type, String qualifiedInterfaceName, RootDoc rootDoc) {

        ClassDoc interfaceClassDoc = rootDoc.classNamed(qualifiedInterfaceName);
        assert interfaceClassDoc != null : qualifiedInterfaceName;

        if (Docs.isSubclassOf(type.classDoc, interfaceClassDoc)) return true;

        ClassDoc adaptTo = type.adaptTo;
        if (adaptTo != null) {
            if (Docs.isSubclassOf(adaptTo, interfaceClassDoc)) return true;
        }

        return false;
    }

    private static AntType
    parseType(Element taskdefElement, RootDoc rootDoc) throws Longjump {

        final String taskName;
        {
            taskName = taskdefElement.getAttribute("name");
            if (taskName == null) {
                rootDoc.printError("<taskdef> lacks the name");
                throw new Longjump();
            }
        }

        final ClassDoc classDoc;
        {
            String classnameAttribute = taskdefElement.getAttribute("classname");
            if (classnameAttribute.length() == 0) {
                rootDoc.printError("<taskdef> lacks the class name");
                throw new Longjump();
            }
            classDoc = rootDoc.classNamed(classnameAttribute);
            if (classDoc == null) {
                rootDoc.printError("Class '" + classnameAttribute + "' not found for  <" + taskName + ">");
                throw new Longjump();
            }
        }

        final ClassDoc adaptTo;
        ADAPT_TO: {
            String adaptToAttribute = taskdefElement.getAttribute("adaptTo");
            if (adaptToAttribute.length() == 0) {
                adaptTo = null;
                break ADAPT_TO;
            }

            adaptTo = rootDoc.classNamed(adaptToAttribute);
            if (adaptTo == null) {
                rootDoc.printError("Class '" + adaptToAttribute + "' not found for <" + taskName + ">");
                throw new Longjump();
            }
        }

        // Deduce attributes and subelements from the special methods that ANT uses.
        return new AntType(
            taskName,
            classDoc,
            adaptTo,
            AntDoclet.characterDataOf(classDoc),
            AntDoclet.attributesOf(classDoc, rootDoc),
            AntDoclet.subelementsOf(classDoc, rootDoc)
        );
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

        final List<AntAttribute>  attributes  = new ArrayList<AntAttribute>();
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
                attributes.add(new AntAttribute(name, md, type, Tags.optionalTag(md, "@ant.group", rootDoc)));
            }
        }
        return attributes;
    }

    public static List<AntSubelement>
    subelementsOf(final ClassDoc classDoc, RootDoc rootDoc) {

        final List<AntSubelement> subelements = new ArrayList<AntSubelement>();
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
            subelements.add(new AntSubelement(md, name, type, Tags.optionalTag(md, "@ant.group", rootDoc)));
        }

        String so = Tags.optionalTag(classDoc, "@ant.subelementOrder", rootDoc);
        if ("inheritedFirst".equals(so)) {
            List<AntSubelement> tmp1 = new ArrayList<AntDoclet.AntSubelement>();
            List<AntSubelement> tmp2 = new ArrayList<AntDoclet.AntSubelement>();
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
