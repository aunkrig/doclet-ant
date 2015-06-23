
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
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
import de.unkrig.commons.io.LineUtil;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.ConsumerWhichThrows;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.lang.protocol.Mapping;
import de.unkrig.commons.lang.protocol.Mappings;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.commons.text.CamelCase;
import de.unkrig.commons.util.collections.IterableUtil;
import de.unkrig.doclet.ant.templates.IndexHtml;
import de.unkrig.doclet.ant.templates.TypeHtml;
import de.unkrig.notemplate.NoTemplate;

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
 * </dl>
 */
public final
class AntDoclet {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    private static final Pattern ADD_TEXT_METHOD_NAME   = Pattern.compile("addText");
    private static final Pattern SET_XYZ_METHOD_NAME    = Pattern.compile("set([A-Z]\\w*)");
    private static final Pattern ADD_XYZ_METHOD_NAME    = Pattern.compile("add(?:Configured)?([A-Z]\\w*)");
    private static final Pattern CREATE_XYZ_METHOD_NAME = Pattern.compile("create([A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME        = Pattern.compile("add(?:Configured)?");

    private RootDoc                                                              rootDoc;
    private final File                                                           antlibFile;
    private final File                                                           destination;
    @Nullable private String                                                     docTitle;
    private final String                                                         windowTitle;
    private final Map<String /*packageName*/, URL>                               externalJavadocs;
    private final Mapping<ClassDoc, URL>                                         externalAntdocs;
    private final Mapping<String /*qualifiedClassName*/, String /*antTypeName*/> knownTypes;

    public
    AntDoclet(
        RootDoc          rootDoc,
        File             antlibFile,
        File             destination,
        @Nullable String docTitle,
        String           windowTitle,
        Map<String, URL> externalJavadocs
    ) {

        this.rootDoc          = rootDoc;
        this.antlibFile       = antlibFile;
        this.destination      = destination;
        this.docTitle         = docTitle;
        this.windowTitle      = windowTitle;
        this.externalJavadocs = externalJavadocs;

        {
            final Properties p = new Properties();

            InputStream is = this.getClass().getResourceAsStream("external-antdocs.properties");
            assert is != null;
            try {
                p.load(is);
                is.close();
            } catch (IOException ioe) {
                throw new ExceptionInInitializerError(ioe);
            } finally {
                try { is.close(); } catch (Exception e) {}
            }

            Map<ClassDoc, URL> m = new HashMap<ClassDoc, URL>();
            for (Entry<Object, Object> e : p.entrySet()) {
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

    /**
     * Representation of a "group of types", e.g. "tasks", "chainable readers", etc.
     */
    public static final
    class AntTypeGroup {

        final String               name;
        final List<AntType>        types;
        final String               typeGroupHeading;
        public final MessageFormat typeTitleMf;
        public final MessageFormat typeHeadingMf;

        /**
         * @param typeGroupHeading May contain inline tags
         * @param typeTitleMf      Must not contain any inline tags; "<code>{0}</code>" maps to the type name
         * @param typeHeadingMf    May contain inline tags; "<code>{0}</code>" maps to the type name
         */
        public
        AntTypeGroup(
            String        name,
            List<AntType> types,
            String        typeGroupHeading,
            String        typeTitleMf,
            String        typeHeadingMf
        ) {
            this.name             = name;
            this.types            = types;
            this.typeGroupHeading = typeGroupHeading;
            this.typeTitleMf      = new MessageFormat(typeTitleMf);
            this.typeHeadingMf    = new MessageFormat(typeHeadingMf);
        }

        @Override public String
        toString() {
            return this.name;
        }
    }

    public static final
    class AntType {

        public final String               name;
        public final ClassDoc             classDoc;
        @Nullable private ClassDoc        adaptTo;
        @Nullable public final MethodDoc  characterData;
        public final List<AntAttribute>   attributes;
        public final List<AntSubelement>  subelements;

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

        if ("-antlib-file".equals(option)) return 2;
        if ("-d".equals(option)) return 2;
        if ("-link".equals(option)) return 2;
        if ("-linkoffline".equals(option)) return 3;
        if ("-doctitle".equals(option)) return 2;
        if ("-windowtitle".equals(option)) return 2;

        return 0;
    }

    /**
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static boolean
    start(final RootDoc rootDoc) throws IOException, ParserConfigurationException, SAXException {

        File                                              antlibFile       = new File("antlib.xml");
        File                                              destination      = new File(".");
        final Map<String /*packageName*/, URL /*target*/> externalJavadocs = new HashMap<String, URL>();
        String                                            docTitle         = null;
        String                                            windowTitle      = "Generated Documentation (Untitled)";

        for (String[] option : rootDoc.options()) {
            if ("-antlib-file".equals(option[0])) {
                antlibFile = new File(option[1]);
            } else
            if ("-d".equals(option[0])) {
                destination = new File(option[1]);
            } else
            if ("-link".equals(option[0])) {
                URL targetUrl = new URL(option[1] + '/');
                AntDoclet.readExternalJavadocs(targetUrl, targetUrl, externalJavadocs, rootDoc);
            } else
            if ("-linkoffline".equals(option[0])) {
                URL targetUrl      = new URL(option[1] + '/');
                URL packageListUrl = new URL(option[2] + '/');

                AntDoclet.readExternalJavadocs(targetUrl, packageListUrl, externalJavadocs, rootDoc);
            } else
            if ("-doctitle".equals(option[0])) {
                docTitle = option[1];
            } else
            if ("-windowtitle".equals(option[0])) {
                windowTitle = option[1];
            } else
            {

                // It is quite counterintuitive, but 'options()' returns ALL options, not only those which
                // qualified by 'optionLength()'.
                ;
            }
        }

        new AntDoclet(rootDoc, antlibFile, destination, docTitle, windowTitle, externalJavadocs).start2();

        return true;
    }

    private void
    start2() throws IOException, ParserConfigurationException, SAXException {

        if (!this.destination.isDirectory()) {
            if (!this.destination.mkdirs()) {
                throw new IOException("Cannot create destination directory \"" + this.destination + "\"");
            }
        }

        IoUtil.copy(
            AntDoclet.class.getClassLoader().getResourceAsStream("de/unkrig/doclet/ant/templates/stylesheet.css"),
            true, // closeInputStream
            new File(this.destination, "stylesheet.css"),
            false // append
        );

        NoTemplate.render(
            IndexHtml.class,
            new File(this.destination, "index.html"),
            new ConsumerWhichThrows<IndexHtml, RuntimeException>() {

                @Override public void
                consume(IndexHtml indexHtml) throws RuntimeException { indexHtml.render(AntDoclet.this.windowTitle); }
            }
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
                tasks.add(this.parseType(taskdefElement, this.rootDoc));
            } catch (Longjump l) {}
        }

        for (Element typedefElement : IterableUtil.concat(
            AntDoclet.<Element>nl2i(document.getElementsByTagName("typedef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("componentdef"))
        )) {
            AntType type;
            try {
                type = this.parseType(typedefElement, this.rootDoc);
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
            "Tasks",
            "Task \"&lt;{0}&gt;\"",
            "Task \"<code>&lt;{0}&gt;</code>\""
        ));
        antTypeGroups.add(new AntTypeGroup(
            "resourceCollections",
            resourceCollections,
            "Resource collections",
            "Resource collection \"&lt;{0}&gt;\"",
            "Resource collection \"<code>&lt;{0}&gt;</code>\""
        ));
        antTypeGroups.add(new AntTypeGroup(
            "chainableReaders",
            chainableReaders,
            "Chainable readers",
            "Chainable reader \"&lt;{0}&gt;\"",
            "Chainable reader \"<code>&lt;{0}&gt;</code>\""
        ));
        antTypeGroups.add(new AntTypeGroup(
            "conditions",
            conditions,
            "Conditions",
            "Condition \"&lt;{0}&gt;\"",
            "Condition \"<code>&lt;{0}&gt;</code>\""
        ));
        antTypeGroups.add(new AntTypeGroup(
            "otherTypes",
            otherTypes,
            "Other types",
            "Type \"&lt;{0}&gt;\"",
            "Type \"<code>&lt;{0}&gt;</code>\""
        ));

        for (final AntTypeGroup typeGroup : antTypeGroups) {

            for (final AntType antType : typeGroup.types) {

                // Because the HTML page hierarchy and the fragment identifier names are different from the standard
                // JAVADOC structure, we must have a custom link maker.
                final LinkMaker linkMaker = this.linkMaker(antType, typeGroup, antTypeGroups, this.rootDoc);

                final Html html = new Html(new Html.ExternalJavadocsLinkMaker(this.externalJavadocs, linkMaker));

                NoTemplate.render(
                    TypeHtml.class,
                    new File(this.destination, typeGroup.name + '/' + antType.name + ".html"),
                    new ConsumerWhichThrows<TypeHtml, RuntimeException>() {

                        @Override public void
                        consume(TypeHtml typeHtml) throws RuntimeException {
                            typeHtml.render(typeGroup, antType, html, AntDoclet.this.rootDoc);
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
        IoUtil.outputFilePrintWriter(
            new File(this.destination, "alldefinitions-frame.html"),
            Charset.forName("ISO8859-1"),
            new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                @Override public void
                consume(PrintWriter pw) {

                    pw.println("<!DOCTYPE html>");
                    pw.println("  <html lang=\"en\">");
                    pw.println("  <head>");
                    pw.println("    <!-- Generated by the ant doclet; see http://doclet.unkrig.de/ -->");
                    pw.println("    <title>All definitions</title>");
                    pw.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\">");
                    pw.println("  </head>");
                    pw.println();
                    pw.println("  <body>");
                    pw.println("    <dl>");
                    pw.println();
                    for (AntTypeGroup typeGroup : antTypeGroups) {

                        if (typeGroup.types.isEmpty()) continue;

                        pw.println("      <dt>" + typeGroup.typeGroupHeading + "</dt>");
                        for (final AntType antType : typeGroup.types) {
                            try {
                                pw.println("      <dd><code>" + html.makeLink(
                                    AntDoclet.this.rootDoc,
                                    antType.classDoc,
                                    false,             // plain
                                    null,              // label
                                    "definitionFrame", // target
                                    AntDoclet.this.rootDoc
                                ) + "</code></dd>");
                            } catch (Longjump l) {}
                        }
                    }
                    pw.println("    </dl>");
                    pw.println("  </body>");
                    pw.println("</html>");
                }
            },
            true // createMissingParentDirectories
        );

        // Generate the document that is initially loaded into the "right frame" and displays all type summaries
        // (type name and first sentence of description).
        final String docTitle2 = this.docTitle;
        IoUtil.outputFilePrintWriter(
            new File(this.destination, "overview-summary.html"),
            Charset.forName("ISO8859-1"),
            new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                @Override public void
                consume(PrintWriter pw) {

                    pw.println("<!DOCTYPE html>");
                    pw.println("  <html lang=\"en\">");
                    pw.println("  <head>");
                    pw.println("    <!-- Generated by the ant doclet; see http://doclet.unkrig.de/ -->");
                    pw.println("    <title>All definitions</title>");
                    pw.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"stylesheet.css\">");
                    pw.println("  </head>");
                    pw.println();
                    pw.println("  <body>");
                    pw.println("    <p>OVERVIEW</p>");
                    pw.println();

                    if (docTitle2 != null) {
                        pw.println("<h1>" + docTitle2 + "</h1>");
                        pw.println();
                    }

                    for (AntTypeGroup typeGroup : antTypeGroups) {

                        if (typeGroup.types.isEmpty()) continue;

                        pw.println("    <h2>" + typeGroup.typeGroupHeading + " summary</h2>");
                        pw.println("    <dl>");
                        for (final AntType antType : typeGroup.types) {
                            try {
                                pw.println("      <dt><code>" + html.makeLink(
                                    AntDoclet.this.rootDoc,
                                    antType.classDoc,
                                    false, // plain
                                    null,  // label
                                    null,  // target
                                    AntDoclet.this.rootDoc
                                ) + "</code></dt>");
                                pw.println("      <dd>" + html.fromTags(
                                    antType.classDoc.firstSentenceTags(), // tags
                                    antType.classDoc,                     // ref
                                    AntDoclet.this.rootDoc                // rootDoc
                                ) + "</dd>");
                            } catch (Longjump l) {}
                        }
                        pw.println("    </dl>");
                    }
                    pw.println("  </body>");
                    pw.println("</html>");
                }
            },
            true // createMissingParentDirectories
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

                            if (t.classDoc != toClass) continue;

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

                    rootDoc.printError(from.position(), (
                        "Linking from '"
                        + from
                        + "' to '"
                        + to
                        + "': "
                        + toMethod
                        + "' is not an attribute setter nor a subelement adder/creator"
                    ));
                    throw new Longjump();
                }

                // Leave references to other elements, e.g. enum constants or constants, "unlinked", i.e. print
                // the bare label without "<a href=...>".
                return null;
            }

            @Override public String
            makeDefaultLabel(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

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

    private AntType
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
                rootDoc.printError("Class '" + classnameAttribute + "' not found for <anttask> '" + taskName + "'");
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
                rootDoc.printError("Class '" + adaptToAttribute + "' not found for <anttask> '" + taskName + "'");
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

    /**
     * Reads package names from "<var>packageListUrl</var>/package-list" and puts them into the {@code
     * externalJavadocs} map.
     */
    private static void
    readExternalJavadocs(
        URL              targetUrl,
        URL              packageListUrl,
        Map<String, URL> externalJavadocs,
        RootDoc          rootDoc
    ) throws IOException {

        List<String> packageNames = LineUtil.readAllLines(
            new InputStreamReader(new URL(packageListUrl, "package-list").openStream()),
            true                                                                         // closeReader
        );

        for (String packageName : packageNames) {
            URL prev = externalJavadocs.put(packageName, targetUrl);
            if (prev != null && !prev.equals(targetUrl)) {
                rootDoc.printError((
                    "Inconsistent offline links: Package '"
                    + packageName
                    + "' was first linked to '"
                    + prev
                    + "', now to '"
                    + targetUrl
                    + "'"
                ));
            }
        }
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
        for (MethodDoc md : Docs.methods(classDoc, true, true)) {

            String      methodName       = md.name();
            Parameter[] methodParameters = md.parameters();

            Matcher m;
            if (
                (m = AntDoclet.SET_XYZ_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 1
            ) {
                String name = CamelCase.toLowerCamelCase(m.group(1));
                Type   type = methodParameters[0].type();

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
                String name = CamelCase.toLowerCamelCase(m.group(1));
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
                String name = CamelCase.toLowerCamelCase(m.group(1));
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
