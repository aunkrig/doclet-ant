
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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.LanguageVersion;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.doclet.html.Html.LinkMaker;
import de.unkrig.commons.file.FileUtil;
import de.unkrig.commons.io.IoUtil;
import de.unkrig.commons.io.LineUtil;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.ConsumerWhichThrows;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.commons.text.CamelCase;
import de.unkrig.commons.text.pattern.PatternUtil;
import de.unkrig.commons.util.collections.IterableUtil;

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

    private static final Pattern SET_XYZ_METHOD_NAME    = Pattern.compile("set([A-Z]\\w*)");
    private static final Pattern ADD_TEXT_METHOD_NAME   = Pattern.compile("addText");
    private static final Pattern ADD_METHOD_NAME        = Pattern.compile("add(?:Configured)?");
    private static final Pattern ADD_XYZ_METHOD_NAME    = Pattern.compile("add(?:Configured)?([A-Z]\\w*)");
    private static final Pattern CREATE_XYZ_METHOD_NAME = Pattern.compile("create([A-Z]\\w*)");

    private static final
    class AntTypeGroup {

        final String        name;
        final List<AntType> types;
        final String        typeGroupHeading;
        final MessageFormat typeTitleMf;
        final MessageFormat typeHeadingMf;

        /**
         * @param tGH           May contain inline tags
         * @param typeTitleMf   Must not contain any inline tags; "<code>{0}</code>" maps to the type name
         * @param typeHeadingMf May contain inline tags; "<code>{0}</code>" maps to the type name
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
    }

    private static final
    class AntType {

        final String               name;
        final ClassDoc             classDoc;
        @Nullable private ClassDoc adaptTo;
        @Nullable final MethodDoc  characterData;
        final List<AntAttribute>   attributes;
        final List<AntSubelement>  subelements;

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
    }

    private static final
    class AntAttribute {

        final String    name;
        final MethodDoc methodDoc;
        final Type      type;

        public
        AntAttribute(String name, MethodDoc methodDoc, Type type) {
            this.name      = name;
            this.methodDoc = methodDoc;
            this.type      = type;
        }
    }

    private static final
    class AntSubelement {

        final MethodDoc        methodDoc;
        @Nullable final String name;
        final Type             type;

        public
        AntSubelement(MethodDoc methodDoc, @Nullable String name, Type type) {
            this.methodDoc = methodDoc;
            this.name      = name;
            this.type      = type;
        }
    }

    /**
     * Doclets are never instantiated.
     */
    private AntDoclet() {}

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

        if (!destination.isDirectory()) {
            if (!destination.mkdirs()) {
                throw new IOException("Cannot create destination directory \"" + destination + "\"");
            }
        }

        IoUtil.copy(
            AntDoclet.class.getClassLoader().getResourceAsStream("de/unkrig/doclet/ant/templates/stylesheet.css"),
            true, // closeInputStream
            new File(destination, "stylesheet.css"),
            false // append
        );

        {
            String resourceName = "de/unkrig/doclet/ant/templates/index.html";

            Reader in = new InputStreamReader(AntDoclet.class.getClassLoader().getResourceAsStream(resourceName));
            try {
                FileWriter out = new FileWriter(new File(destination, "index.html"));
                try {
                    PatternUtil.replaceAll(resourceName, in, Pattern.compile("@WINDOW_TITLE@"), windowTitle, out);
                    out.close();
                } finally {
                    try { out.close(); } catch (Exception e) {}
                }
                in.close();
            } finally {
                try { in.close(); } catch (Exception e) {}
            }
        }

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder        documentBuilder        = documentBuilderFactory.newDocumentBuilder();
        Document               document               = documentBuilder.parse(antlibFile);

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
                tasks.add(AntDoclet.parseType(taskdefElement, rootDoc));
            } catch (Longjump l) {}
        }

        for (Element typedefElement : IterableUtil.concat(
            AntDoclet.<Element>nl2i(document.getElementsByTagName("typedef")),
            AntDoclet.<Element>nl2i(document.getElementsByTagName("componentdef"))
        )) {
            AntType type;
            try {
                type = AntDoclet.parseType(typedefElement, rootDoc);
            } catch (Longjump l) {
                continue;
            }

            if (AntDoclet.typeIs(type, "org.apache.tools.ant.Task", rootDoc)) {
                tasks.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.types.ResourceCollection", rootDoc)) {
                resourceCollections.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.filters.ChainableReader", rootDoc)) {
                chainableReaders.add(type);
            } else
            if (AntDoclet.typeIs(type, "org.apache.tools.ant.taskdefs.condition.Condition", rootDoc)) {
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
                final LinkMaker linkMaker = linkMaker(antType, typeGroup, antTypeGroups, rootDoc);

                final Html html = new Html(new Html.ExternalJavadocsLinkMaker(externalJavadocs, linkMaker));

                final Set<ClassDoc> seenTypes = new HashSet<ClassDoc>();

                FileUtil.printToFile(
                    new File(destination, typeGroup.name + '/' + antType.name + ".html"),
                    Charset.forName("ISO8859-1"),
                    new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                        @Override public void
                        consume(PrintWriter pw) {

                            String typeTitle   = typeGroup.typeTitleMf.format(new String[] { antType.name });
                            String typeHeading = typeGroup.typeHeadingMf.format(new String[] { antType.name });

                            pw.println("<!DOCTYPE html>");
                            pw.println("  <html lang=\"en\">");
                            pw.println("  <head>");
                            pw.println("    <!-- Generated by the ant doclet; see http://doclet.unkrig.de/ -->");
                            pw.println("    <title>" + typeTitle + "</title>");
                            pw.println("    <meta name=\"keywords\" content=\"" + antType.name + "\">");
                            pw.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"../stylesheet.css\">");
                            pw.println("  </head>");
                            pw.println();
                            pw.println("  <body>");
                            pw.println("    <p><a href=\"../overview-summary.html\">OVERVIEW</a> DEFINITION</p>");
                            pw.println("    <h1>" + typeHeading + "</h1>");
                            pw.println();

                            try {
                                this.printType(antType, html, rootDoc, pw);
                            } catch (Longjump l) {}

                            pw.println("  </body>");
                            pw.println("</html>");
                        }

                        private void
                        printType(final AntType antType, final Html html, final RootDoc rootDoc, PrintWriter pw)
                        throws Longjump {

                            pw.write(html.fromTags(antType.classDoc.inlineTags(), antType.classDoc, rootDoc));

                            MethodDoc characterData = antType.characterData;
                            if (characterData != null) {
                                pw.println();
                                pw.println("    <h3>Text between start and end tag</h3>");
                                pw.println();
                                pw.println("    <dl>");
                                this.printCharacterData(characterData, html, rootDoc, pw);
                                pw.println("    </dl>");
                            }

                            if (!antType.attributes.isEmpty()) {
                                pw.println();
                                pw.println("    <h3>Attributes</h3>");
                                pw.println();
                                pw.println("    <p>Default values are <u>underlined</u>.</p>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntAttribute attribute : antType.attributes) {
                                    this.printAttribute(attribute, html, rootDoc, pw);
                                }
                                pw.println("    </dl>");
                            }

                            if (!antType.subelements.isEmpty()) {
                                pw.println();
                                pw.println("    <h3>Subelements</h3>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntSubelement subelement : antType.subelements) {
                                    this.printSubelement(subelement, html, rootDoc, pw);
                                }
                                pw.println("    </dl>");
                            }
                        }

                        /**
                         * Prints documentation for "character data" (nested text between opening and closing tags).
                         */
                        private void
                        printCharacterData(
                            MethodDoc     characterData,
                            final Html    html,
                            final RootDoc rootDoc,
                            PrintWriter   pw
                        ) {

                            // See http://ant.apache.org/manual/develop.html#set-magic

                            // Generate character data description.
                            try {
                                String
                                attributeHtmlText = html.generateFor(characterData, rootDoc);

                                pw.println("      <dd>");
                                pw.println("        " + attributeHtmlText.replaceAll("\\s+", " "));
                                pw.println("      </dd>");
                            } catch (Longjump l) {}
                        }

                        private void
                        printAttribute(AntAttribute attribute, final Html html, final RootDoc rootDoc, PrintWriter pw) {

                            String defaultValueHtmlText = this.defaultValueHtmlText(attribute.methodDoc, html, rootDoc);

                            // See http://ant.apache.org/manual/develop.html#set-magic
                            String rhs;

                            Type   attributeType              = attribute.type;
                            String qualifiedAttributeTypeName = attributeType.qualifiedTypeName();
                            if (
                                "boolean".equals(qualifiedAttributeTypeName)
                                || "java.lang.Boolean".equals(qualifiedAttributeTypeName)
                            ) {
                                if (Boolean.parseBoolean(defaultValueHtmlText)) {
                                    rhs = "<u>true</u>|false";
                                } else {
                                    rhs = "true|<u>false</u>";
                                }
                            } else
                            if (
                                attributeType.isPrimitive()
                                || "java.lang.Byte".equals(qualifiedAttributeTypeName)
                                || "java.lang.Short".equals(qualifiedAttributeTypeName)
                                || "java.lang.Long".equals(qualifiedAttributeTypeName)
                                || "java.lang.Float".equals(qualifiedAttributeTypeName)
                                || "java.lang.Double".equals(qualifiedAttributeTypeName)
                            ) {
                                rhs = "<var>N</var>";
                                if (defaultValueHtmlText != null) rhs += "|<u>" + defaultValueHtmlText + "</u>";
                            } else
                            if (
                                "java.io.File".equals(qualifiedAttributeTypeName)
                                || "org.apache.tools.ant.types.Resource".equals(qualifiedAttributeTypeName)
                                || "org.apache.tools.ant.types.Path".equals(qualifiedAttributeTypeName)
                                || "java.lang.Class".equals(qualifiedAttributeTypeName)
                                || "java.lang.String".equals(qualifiedAttributeTypeName)
                                || "java.lang.Object".equals(qualifiedAttributeTypeName)
                                || "de.unkrig.antcontrib.util.Regex".equals(qualifiedAttributeTypeName)
                            ) {
                                rhs = "<var>" + CamelCase.toHyphenSeparated(attributeType.simpleTypeName()) + "</var>";
                                if (defaultValueHtmlText != null) rhs += "|<u>" + defaultValueHtmlText + "</u>";
                            } else
                            if (attributeType instanceof Doc && ((Doc) attributeType).isEnum()) {
                                StringBuilder sb = new StringBuilder();
                                for (FieldDoc enumConstant : ((ClassDoc) attributeType).enumConstants()) {
                                    if (sb.length() > 0) sb.append('|');
                                    boolean isDefault = enumConstant.name().equals(defaultValueHtmlText);
                                    if (isDefault) sb.append("<u>");
                                    sb.append(enumConstant.name());
                                    if (isDefault) sb.append("</u>");
                                }
                                rhs = sb.toString();
                            } else
                            {
                                try {
                                    rhs = (
                                        "<var>"
                                        + html.makeLink(
                                            attribute.methodDoc,
                                            this.getSingleStringParameterConstructor(
                                                (ClassDoc) attributeType,
                                                attribute.methodDoc,
                                                rootDoc
                                            ),
                                            CamelCase.toHyphenSeparated(attributeType.simpleTypeName()),
                                            null, rootDoc
                                        )
                                        + "</var>"
                                    );
                                } catch (Longjump l) {
                                    rhs = (
                                        "<var>"
                                        + CamelCase.toHyphenSeparated(attributeType.simpleTypeName())
                                        + "</var>"
                                    );
                                }

                                if (defaultValueHtmlText != null) rhs += "|<u>" + defaultValueHtmlText + "</u>";
                            }

                            pw.println("      <dt>");
                            pw.println("        <a name=\"" + attribute.name + "\" />");
                            pw.println("        <code>" + attribute.name + "=\"" + rhs + "\"</code>");
                            pw.println("      </dt>");

                            // Generate attribute description.
                            try {
                                String
                                attributeHtmlText = html.generateFor(attribute.methodDoc, rootDoc);

                                pw.println("      <dd>");
                                pw.println("        " + attributeHtmlText.replaceAll("\\s+", " "));
                                pw.println("      </dd>");
                            } catch (Longjump l) {}
                        }

                        private void
                        printSubelement(
                            AntSubelement subelement,
                            final Html    html,
                            final RootDoc rootDoc,
                            PrintWriter   pw
                        ) {
                            ClassDoc subelementTypeClassDoc = subelement.type.asClassDoc();

                            pw.println("      <dt>");
                            if (subelement.name != null) {
                                pw.println("        <a name=\"&lt;" + subelement.name + "&gt;\" />");
                                pw.println("        <code>&lt;" + subelement.name + "></code>");
                            } else {
                                pw.println("        <a name=\"" + subelementTypeClassDoc.qualifiedName() + "\" />");
                                try {
                                    pw.println(
                                        "        Any <code>"
                                        + html.makeLink(antType.classDoc, subelementTypeClassDoc, null, null, rootDoc)
                                        + "</code>"
                                    );
                                } catch (Longjump l) {
                                    pw.println(
                                        "        Any <code>"
                                        + subelementTypeClassDoc.qualifiedName()
                                        + "</code>"
                                    );
                                }
                            }
                            pw.println("      </dt>");

                            // Generate subelement description.
                            try {
                                String subelementHtmlText = html.generateFor(
                                    subelement.methodDoc,
                                    rootDoc
                                );
                                pw.println("      <dd>");
                                pw.println("        " + subelementHtmlText.replaceAll("\\s+", " "));
                                pw.println("      </dd>");
                            } catch (Longjump e) {}

                            // Generate subelement type description.
                            if (subelementTypeClassDoc.isIncluded()) {

                                if (!seenTypes.add(subelementTypeClassDoc)) {
                                    pw.println(
                                        "      <dd>(The configuration options for this element are the same <a href=\"#"
                                        + subelementTypeClassDoc.qualifiedName()
                                        + "\">as described above</a>.)</dd>"
                                    );
                                    return;
                                }


                                pw.println("      <dd>");
                                pw.println("        <a name=\"" + subelementTypeClassDoc.qualifiedName() + "\" />");
                                try {
                                    String subelementTypeHtmlText = html.fromTags(
                                        subelementTypeClassDoc.inlineTags(),
                                        subelementTypeClassDoc,
                                        rootDoc
                                    );
                                    pw.println("        " + subelementTypeHtmlText.replaceAll("\\s+", " "));
                                } catch (Longjump l) {}
                                pw.println("      </dd>");

                                // Subelement's character data.
                                MethodDoc
                                characterData = AntDoclet.characterDataOf(subelementTypeClassDoc);
                                if (characterData != null) {
                                    pw.println("<dd><b>Text between start and end tag:</b></dd>");
                                    pw.println("<dd>");
                                    pw.println("  <dl>");
                                    this.printCharacterData(characterData, html, rootDoc, pw);
                                    pw.println("  </dl>");
                                    pw.println("</dd>");
                                }

                                // Subelement's attributes' descriptions.
                                List<AntAttribute>
                                subelementAttributes = AntDoclet.attributesOf(subelementTypeClassDoc);
                                if (!subelementAttributes.isEmpty()) {
                                    pw.println("<dd><b>Attributes:</b></dd>");
                                    pw.println("<dd>");
                                    pw.println("  <dl>");
                                    for (AntAttribute subelementAttribute : subelementAttributes) {
                                        this.printAttribute(subelementAttribute, html, rootDoc, pw);
                                    }
                                    pw.println("  </dl>");
                                    pw.println("</dd>");
                                }

                                // Subelement's subelements' descriptions.
                                List<AntSubelement>
                                subelementSubelements = AntDoclet.subelementsOf(subelementTypeClassDoc);
                                if (!subelementSubelements.isEmpty()) {
                                    pw.println("<dd><b>Subelements:</b></dd>");
                                    pw.println("<dd>");
                                    pw.println("  <dl>");
                                    for (AntSubelement subelementSubelement : subelementSubelements) {
                                        this.printSubelement(subelementSubelement, html, rootDoc, pw);
                                    }
                                    pw.println("  </dl>");
                                    pw.println("</dd>");
                                }
                            }
                        }

                        @Nullable private String
                        defaultValueHtmlText(MethodDoc characterData, final Html html, final RootDoc rootDoc) {

                            Tag[] defaultValueTag = characterData.tags("@de.unkrig.doclet.ant.defaultValue");
                            if (defaultValueTag.length == 0) return null;

                            if (defaultValueTag.length > 1) {
                                rootDoc.printWarning("Only one '@de.unkrig.doclet.ant.defaultValue' tag allowed");
                            }

                            try {
                                return html.fromJavadocText(
                                    defaultValueTag[0].text(),
                                    characterData,
                                    rootDoc
                                );
                            } catch (Longjump e) {
                                return null;
                            }
                        }

                        private ConstructorDoc
                        getSingleStringParameterConstructor(ClassDoc classDoc, Doc ref, DocErrorReporter errorReporter)
                        throws Longjump {

                            for (ConstructorDoc cd : classDoc.constructors()) {

                                if (
                                    cd.parameters().length == 1
                                    && "java.lang.String".equals(cd.parameters()[0].type().qualifiedTypeName())
                                ) return cd;
                            }
                            errorReporter.printError(
                                ref.position(),
                                "Resolving '" + ref + "': '" + classDoc + "' has no single-string-parameter constructor"
                            );
                            throw new Longjump();
                        }
                    }
                );
            }
        }

        if (document.getElementsByTagName("macrodef").getLength() > 0) {
            rootDoc.printWarning("<macrodef>s are not yet supported");
        }

        if (document.getElementsByTagName("presetdef").getLength() > 0) {
            rootDoc.printWarning("<presetdef>s are not yet supported");
        }

        if (document.getElementsByTagName("scriptdef").getLength() > 0) {
            rootDoc.printWarning("<scriptdef>s are not yet supported");
        }

        final LinkMaker linkMaker = linkMaker(null, null, antTypeGroups, rootDoc);

        final Html html = new Html(new Html.ExternalJavadocsLinkMaker(externalJavadocs, linkMaker));

        // Generate the document that is loaded into the "left frame" and displays all types in groups.
        FileUtil.printToFile(
            new File(destination, "alldefinitions-frame.html"),
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
                                pw.println((
                                    "      <dd><code>"
                                    + html.makeLink(rootDoc, antType.classDoc, null, "definitionFrame", rootDoc)
                                    + "</code></dd>"
                                ));
                            } catch (Longjump l) {}
                        }
                    }
                    pw.println("    </dl>");
                    pw.println("  </body>");
                    pw.println("</html>");
                }
            }
        );

        // Generate the document that is initially loaded into the "right frame" and displays all type summaries
        // (type name and first sentence of description).
        final String docTitle2 = docTitle;
        FileUtil.printToFile(
            new File(destination, "overview-summary.html"),
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
                    pw.println("    <p>OVERVIEW DEFINITION</p>");
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
                                pw.println((
                                    "      <dt><code>"
                                    + html.makeLink(rootDoc, antType.classDoc, null, null, rootDoc)
                                    + "</code></dt>"
                                ));
                                pw.println((
                                    "      <dd>"
                                    + html.fromTags(antType.classDoc.firstSentenceTags(), antType.classDoc, rootDoc)
                                    + "</dd>"
                                ));
                            } catch (Longjump l) {}
                        }
                        pw.println("    </dl>");
                    }
                    pw.println("  </body>");
                    pw.println("</html>");
                }
            }
        );

        return true;
    }

    private static LinkMaker
    linkMaker(
        @Nullable final AntType      antType,
        @Nullable final AntTypeGroup typeGroup,
        final List<AntTypeGroup>     antTypeGroups,
        RootDoc                      rootDoc
    ) {

        return new LinkMaker() {

            @Override public String
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

                            rootDoc.printError(from.position(), (
                                "'"
                                + to
                                + "' does not designate an attribute or subelement of type '<"
                                + t.name
                                + ">'"
                            ));
                        }
                        throw new Longjump();
                    }

                    rootDoc.printError(
                        from.position(),
                        "Linking from '" + from + "' to '" + to + "': " + toClass + "' is not an ANT type"
                    );
                    throw new Longjump();
                }

                rootDoc.printError(
                    from.position(),
                    "'" + to + "' does not designate a type, attribute or subelement"
                );
                throw new Longjump();
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

                rootDoc.printError(
                    from.position(),
                    "'" + to + "' does not designate a task, attribute or subelement"
                );
                throw new Longjump();
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
            AntDoclet.attributesOf(classDoc),
            AntDoclet.subelementsOf(classDoc)
        );
    }

    /**
     * @return The {@link MethodDoc} of the "{@code addText(String)}", or {@code null}
     */
    @Nullable private static MethodDoc
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

    private static List<AntAttribute>
    attributesOf(final ClassDoc classDoc) {

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

                attributes.add(new AntAttribute(name, md, type));
            }
        }
        return attributes;
    }

    private static List<AntSubelement>
    subelementsOf(final ClassDoc classDoc) {

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

                subelements.add(new AntSubelement(md, name, type));
            } else
            if (
                (m = AntDoclet.ADD_METHOD_NAME.matcher(methodName)).matches()
                && methodParameters.length == 1
            ) {
                Type type = methodParameters[0].type();

                subelements.add(new AntSubelement(md, null, type));
            } else
            if (
                (m = AntDoclet.ADD_XYZ_METHOD_NAME.matcher(methodName)).matches()
                && !AntDoclet.ADD_TEXT_METHOD_NAME.matcher(methodName).matches()
                && methodParameters.length == 1
            ) {
                String name = CamelCase.toLowerCamelCase(m.group(1));
                Type   type = methodParameters[0].type();

                subelements.add(new AntSubelement(md, name, type));
            }
        }
        return subelements;
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

    private static <N extends Node> Iterable<N>
    nl2i(final NodeList nl) {

        return new Iterable<N>() {

            @Override public Iterator<N>
            iterator() {

                return new Iterator<N>() {

                    int idx;

                    @Override public boolean
                    hasNext() { return this.idx < nl.getLength(); }

                    @Override public N
                    next() {

                        if (this.idx >= nl.getLength()) throw new NoSuchElementException();

                        @SuppressWarnings("unchecked") N result = (N) nl.item(this.idx++);
                        return result;
                    }

                    @Override public void
                    remove() { throw new UnsupportedOperationException("remove"); }
                };
            }
        };
    }
}
