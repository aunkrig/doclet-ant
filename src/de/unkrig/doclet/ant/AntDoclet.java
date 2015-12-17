
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
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
import com.sun.javadoc.Type;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.Tags;
import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.doclet.html.Html.LinkMaker;
import de.unkrig.commons.io.IoUtil;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.ExceptionUtil;
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

    private static final Pattern ADD_TEXT_METHOD_NAME   = Pattern.compile("addText");
    private static final Pattern SET_XYZ_METHOD_NAME    = Pattern.compile("set(?<attributeName>[A-Z]\\w*)");
    private static final Pattern ADD_XYZ_METHOD_NAME    = Pattern.compile("add(?:Configured)?(?<attributeName>[A-Z]\\w*)"); // SUPPRESS CHECKSTYLE LineLength
    private static final Pattern CREATE_XYZ_METHOD_NAME = Pattern.compile("create(?<attributeName>[A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME        = Pattern.compile("add(?:Configured)?");

    private enum Theme { JAVA7, JAVA8 }

    private RootDoc                                rootDoc;
    private final Options                          options;
    private final File                             antlibFile;
    private final Map<String /*packageName*/, URL> externalJavadocs;

    private final Mapping<ClassDoc, URL>                                         externalAntdocs;
    private final Mapping<String /*qualifiedClassName*/, String /*antTypeName*/> knownTypes;
    private final Theme                                                          theme;

    public
    AntDoclet(
        RootDoc          rootDoc,
        Options          options,
        File             antlibFile,
        Map<String, URL> externalJavadocs,
        Theme            theme
    ) throws IOException {

        this.rootDoc          = rootDoc;
        this.options          = options;
        this.antlibFile       = antlibFile;
        this.externalJavadocs = externalJavadocs;
        this.theme            = theme;

        {
            Map<ClassDoc, URL> m = new HashMap<ClassDoc, URL>();

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
                    String href               = (String) e.getValue();

                    ClassDoc cd = rootDoc.classNamed(qualifiedClassName);
                    if (cd == null) {
                        rootDoc.printError("Cannot load \"" + qualifiedClassName + "\" for external HREF");
                        continue;
                    }

                    try {
                        m.put(cd, new URL(href));
                    } catch (MalformedURLException mue) {
                        throw new ExceptionInInitializerError(mue);
                    }
                }
            }

            {
                Properties properties;
                try {
                    properties = this.loadPropertiesFromResource((
                        "org/apache/tools/ant/taskdefs/defaults.properties"
                    ));
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

                    try {
                        m.put(cd, new URL("http://ant.apache.org/manual/Tasks/" + taskName + ".html"));
                    } catch (MalformedURLException mue) {
                        throw new ExceptionInInitializerError(mue);
                    }
                }
            }

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

                    try {
                        m.put(cd, new URL("http://ant.apache.org/manual/Types/" + typeName + ".html"));
                    } catch (MalformedURLException mue) {
                        throw new ExceptionInInitializerError(mue);
                    }
                }
            }

            this.externalAntdocs = Mappings.fromMap(m);
        }


        {
            final Properties p = new Properties();
            for (String rn : new String[] {
                "org/apache/tools/ant/listener/defaults.properties",
                "org/apache/tools/ant/taskdefs/defaults.properties",
                "org/apache/tools/ant/types/defaults.properties"
            }) {
                InputStream is = this.getClass().getClassLoader().getResourceAsStream(rn);
                assert is != null : rn;
                try {
                    p.load(is);
                    is.close();
                } catch (IOException ioe) {
                    throw new ExceptionInInitializerError(ioe);
                } finally {
                    try { is.close(); } catch (Exception e) {}
                }
            }

            Map<String /*qualifiedClassName*/, String /*name*/> m = new HashMap<String, String>();
            for (Entry<Object, Object> e : p.entrySet()) {
                String antTypeName        = (String) e.getKey();
                String qualifiedClassName = (String) e.getValue();

                m.put(qualifiedClassName, antTypeName);
            }

            this.knownTypes = Mappings.fromMap(m);
        }
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
        public final String name;

        /**
         * The ANT types that comprise the group.
         */
        public final List<AntType> types;

        /**
         * E.g. {@code "Task"}
         */
        public final String typeGroupName;

        /**
         * E.g. {@code "Tasks"}
         */
        public final String typeGroupHeading;

        /**
         * E.g. <code>"Task \"&amp;lt{0}&amp;gt;\""</code>
         */
        public final MessageFormat typeTitleMf;

        /**
         * E.g. <code>"Task \"&lt;code>&amp;lt;{0}&amp;gt;&lt;/code>\""}
         */
        public final MessageFormat typeHeadingMf;

        /**
         * @param typeGroupName    E.g. {@code "Task"}; may contain inline tags
         * @param typeGroupHeading E.g. {@code "Tasks"}; may contain inline tags
         * @param typeTitleMf      Must not contain any inline tags; "<code>{0}</code>" maps to the type name
         * @param typeHeadingMf    May contain inline tags; "<code>{0}</code>" maps to the type name
         */
        public
        AntTypeGroup(
            String        name,
            List<AntType> types,
            String        typeGroupName,
            String        typeGroupHeading,
            String        typeTitleMf,
            String        typeHeadingMf
        ) {
            this.name             = name;
            this.types            = types;
            this.typeGroupName    = typeGroupName;
            this.typeGroupHeading = typeGroupHeading;
            this.typeTitleMf      = new MessageFormat(typeTitleMf);
            this.typeHeadingMf    = new MessageFormat(typeHeadingMf);
        }

        @Override public String
        toString() {
            return this.name;
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

        public final MethodDoc        methodDoc;
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
            {

                // It is quite counterintuitive, but 'options()' returns ALL options, not only those which
                // qualified by 'optionLength()'.
                ;
            }
        }

        new AntDoclet(rootDoc, options, antlibFile, externalJavadocs, theme).start2();

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
                    true                                                  // createMissingParentDirectories
                );
            }
            break;

        case JAVA8:
            IoUtil.copyResource(
                AntDoclet.class.getClassLoader(),
                "de/unkrig/doclet/ant/theme/java8/stylesheet.css",
                new File(this.options.destination, "stylesheet.css"),
                true                                                  // createMissingParentDirectories
            );
            break;
        }

        IoUtil.copyResource(
            AntDoclet.class.getClassLoader(),
            "de/unkrig/doclet/ant/templates/stylesheet2.css",
            new File(this.options.destination, "stylesheet2.css"),
            true                                                  // createMissingParentDirectories
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

        final List<AntType> tasks               = new ArrayList<AntType>();
        final List<AntType> resourceCollections = new ArrayList<AntType>();
        final List<AntType> chainableReaders    = new ArrayList<AntType>();
        final List<AntType> conditions          = new ArrayList<AntType>();
        final List<AntType> otherTypes          = new ArrayList<AntType>();

        // Now parse the contents of the given ANTLIB file; see
        // https://ant.apache.org/manual/Types/antlib.html
        for (Element taskdefElement : AntDoclet.<Element>nl2i(document.getElementsByTagName("taskdef"))) {
            try {
                tasks.add(AntDoclet.parseType(taskdefElement, this.rootDoc));
            } catch (Longjump l) {}
        }

        for (Element typedefElement : IterableUtil.concat(
            AntDoclet.<Element>nl2i(document.getElementsByTagName("typedef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("componentdef"))
        )) {
            AntType type;
            try {
                type = AntDoclet.parseType(typedefElement, this.rootDoc);
            } catch (Longjump l) {
                continue;
            }

            if (AntDoclet.typeIs(type, "org.apache.tools.ant.Task", this.rootDoc)) {
                tasks.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.types.ResourceCollection", this.rootDoc)) {
                resourceCollections.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.filters.ChainableReader", this.rootDoc)) {
                chainableReaders.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.taskdefs.condition.Condition", this.rootDoc)) {
                conditions.add(type);
            } else
            {
                otherTypes.add(type);
            }
        }

        final List<AntTypeGroup> antTypeGroups = new ArrayList<AntTypeGroup>();
        antTypeGroups.add(new AntTypeGroup(
            "tasks",
            tasks,
            "Task", // typeGroupName
            "Tasks",
            "Task \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.add(new AntTypeGroup(
            "resourceCollections",
            resourceCollections,
            "Resource collection", // typeGroupName
            "Resource collections",
            "Resource collection \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.add(new AntTypeGroup(
            "chainableReaders",
            chainableReaders,
            "Chainable reader", // typeGroupName
            "Chainable readers",
            "Chainable reader \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.add(new AntTypeGroup(
            "conditions",
            conditions,
            "Condition", // typeGroupName
            "Conditions",
            "Condition \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));
        antTypeGroups.add(new AntTypeGroup(
            "otherTypes",
            otherTypes,
            "Other type", // typeGroupName
            "Other types",
            "Type \"&lt;{0}&gt;\"",
            "<code>&lt;{0}&gt;</code>"
        ));

        for (final AntTypeGroup typeGroup : antTypeGroups) {

            for (final ElementWithContext<AntType> atwc : IterableUtil.iterableWithContext(typeGroup.types)) {
                AntType antType = atwc.current();

                // Because the HTML page hierarchy and the fragment identifier names are different from the standard
                // JAVADOC structure, we must have a custom link maker.
                final LinkMaker linkMaker = this.linkMaker(antType, typeGroup, antTypeGroups, this.rootDoc);

                final Html html = new Html(new Html.ExternalJavadocsLinkMaker(this.externalJavadocs, linkMaker));

                NoTemplate.render(
                    TypeHtml.class,
                    new File(this.options.destination, typeGroup.name + '/' + antType.name + ".html"),
                    new ConsumerWhichThrows<TypeHtml, RuntimeException>() {

                        @Override public void
                        consume(TypeHtml typeHtml) throws RuntimeException {
                            typeHtml.render(typeGroup, atwc, html, AntDoclet.this.rootDoc, AntDoclet.this.options);
                        }
                    }
                );
            }
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

        final LinkMaker linkMaker = this.linkMaker(null, null, antTypeGroups, this.rootDoc);

        final Html html = new Html(new Html.ExternalJavadocsLinkMaker(this.externalJavadocs, linkMaker));

        // Generate the document that is loaded into the "left frame" and displays all types in groups.
        NoTemplate.render(
            AllDefinitionsHtml.class,
            new File(this.options.destination, "alldefinitions-frame.html"),
            (AllDefinitionsHtml allDefinitionsHtml) -> {
                allDefinitionsHtml.render(antTypeGroups, AntDoclet.this.rootDoc, AntDoclet.this.options, html);
            }
        );

        // Generate "overview-summary.html" - the document that is initially loaded into the "right frame" and displays
        // all type summaries (type name and first sentence of description).
        NoTemplate.render(
            OverviewSummaryHtml.class,
            new File(this.options.destination, "overview-summary.html"),
            (OverviewSummaryHtml overviewSummaryHtml) -> {
                overviewSummaryHtml.render(antTypeGroups, AntDoclet.this.rootDoc, AntDoclet.this.options, html);
            }
        );
    }

    private LinkMaker
    linkMaker(
        @Nullable final AntType      antType,
        @Nullable final AntTypeGroup typeGroup,
        final List<AntTypeGroup>     antTypeGroups,
        RootDoc                      rootDoc
    ) {

        return new LinkMaker() {

            @Override @Nullable public String
            makeHref(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

                // Link to an ANT type?
                if (to instanceof ClassDoc) {
                    ClassDoc toClass = (ClassDoc) to;
                    for (AntTypeGroup tg : antTypeGroups) {
                        for (AntType t : tg.types) {
                            if (toClass == t.classDoc) {
                                return (
                                    antType == null ? tg.name + '/' + t.name + ".html" :
                                    tg == typeGroup ? t.name + ".html" :
                                    "../" + tg.name + '/' + t.name + ".html"
                                );
                            }
                        }
                    }

                    URL target = AntDoclet.this.externalAntdocs.get(to);
                    if (target != null) return target.toString();

                    rootDoc.printError(from.position(), "'" + to + "' does not designate a type");
                    throw new Longjump();
                }

                if (to instanceof MethodDoc) {
                    MethodDoc toMethod = (MethodDoc) to;
                    ClassDoc  toClass  = toMethod.containingClass();
                    for (AntTypeGroup tg : antTypeGroups) {
                        for (AntType t : tg.types) {

//                            if (t.classDoc != toClass) continue;

                            // Link to an attribute (of the same or a different ANT type)?
                            for (AntAttribute a : t.attributes) {
                                if (a.methodDoc == toMethod) {
                                    String fragment = '#' + a.name;
                                    return (
                                        antType == null ? tg.name + '/' + t.name + fragment :
                                        toClass == antType.classDoc ? fragment :
                                        typeGroup == tg ? t.name + fragment :
                                        "../" + tg.name + '/' + t.name + fragment
                                    );
                                }
                            }

                            // Link to a subelement (of the same or a different ANT type)?
                            for (AntSubelement s : t.subelements) {
                                if (s.methodDoc == toMethod) {
                                    String fragment = (
                                        "#<"
                                        + (s.name != null ? s.name : s.type.asClassDoc().qualifiedName())
                                        + '>'
                                    );
                                    return (
                                        antType == null ? tg.name + '/' + t.name + fragment :
                                        toMethod.containingClass() == antType.classDoc ? fragment :
                                        typeGroup == tg ? antType.name + fragment :
                                        "../" + tg.name + '/' + antType.name + fragment
                                    );
                                }
                            }
                        }
                    }

                    rootDoc.printWarning(from.position(), (
                        "Linking from '"
                        + from
                        + "' to '"
                        + to
                        + "': "
                        + toMethod
                        + "' is not an attribute setter nor a subelement adder/creator"
                    ));
                    return null;
                }

                // Leave references to other elements, e.g. enum constants or constants, "unlinked", i.e. print
                // the bare label without "<a href=...>".
                return null;
            }

            @Override public String
            makeDefaultLabel(Doc from, Doc to, RootDoc rootDoc) {

                if (to instanceof ClassDoc) {
                    ClassDoc toClass = (ClassDoc) to;
                    for (AntTypeGroup atg : antTypeGroups) {
                        for (AntType t : atg.types) {
                            if (toClass == t.classDoc) return "&lt;" + t.name + "&gt;";
                        }
                    }

                    String antTypeName = AntDoclet.this.knownTypes.get(toClass.qualifiedName());
                    if (antTypeName != null) return "&lt;" + antTypeName + "&gt;";

                    if ("org.apache.tools.ant.types.ResourceCollection".equals(toClass.qualifiedName())) {
                        return "resource collection";
                    }
                }

                if (to instanceof MethodDoc) {
                    MethodDoc toMethod = (MethodDoc) to;
                    for (AntTypeGroup atg : antTypeGroups) {
                        for (AntType t : atg.types) {
                            for (AntAttribute a : t.attributes) {
                                if (a.methodDoc == toMethod) return a.name + "=\"...\"";
                            }
                            for (AntSubelement s : t.subelements) {
                                if (s.methodDoc == toMethod) return "&lt;" + s.name + "&gt;";
                            }
                        }
                    }
                }

                // For references to other elements, e.g. enum constants or constants, return the element's name.
                return to.name();
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
                (m = AntDoclet.SET_XYZ_METHOD_NAME.matcher(methodName)).matches()
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
        for (MethodDoc md : Docs.methods(classDoc, true, true)) {

            String      methodName       = md.name();
            Parameter[] methodParameters = md.parameters();

            Matcher m;

            if (
                (m = AntDoclet.CREATE_XYZ_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 0
            ) {
                String name = Notations.fromCamelCase(m.group("attributeName")).toLowerCamelCase();
                Type   type = md.returnType();

                subelements.add(new AntSubelement(md, name, type, Tags.optionalTag(md, "@ant.group", rootDoc)));
            } else
            if (
                (m = AntDoclet.ADD_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 1
            ) {
                Type type = methodParameters[0].type();

                subelements.add(new AntSubelement(md, null, type, Tags.optionalTag(md, "@ant.group", rootDoc)));
            } else
            if (
                (m = AntDoclet.ADD_XYZ_METHOD_NAME.matcher(methodName)).matches()
                && !AntDoclet.ADD_TEXT_METHOD_NAME.matcher(methodName).matches()
                && methodParameters.length == 1
            ) {
                String name = Notations.fromCamelCase(m.group("attributeName")).toLowerCamelCase();
                Type   type = methodParameters[0].type();

                subelements.add(new AntSubelement(md, name, type, Tags.optionalTag(md, "@ant.group", rootDoc)));
            }
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
