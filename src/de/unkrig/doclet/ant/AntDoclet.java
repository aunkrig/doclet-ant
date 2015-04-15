
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
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
import de.unkrig.commons.doclet.Html;
import de.unkrig.commons.doclet.Html.LinkMaker;
import de.unkrig.commons.doclet.Mediawiki;
import de.unkrig.commons.file.FileUtil;
import de.unkrig.commons.io.LineUtil;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.ConsumerWhichThrows;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.commons.text.CamelCase;

/**
 * A doclet that generates documentation for <a href="http://ant.apache.org">APACHE ANT</a> tasks and other artifacts
 * in MEDIAWIKI markup format.
 * <p>
 *   Opens, reads and parses an <a href="http://ant.apache.org/manual/Types/antlib.html">ANTLIB</a> file and generates
 *   one MEDIAWIKI document per type, task, macro, preset and script defined therein.
 * </p>
 */
public final
class AntDoclet {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    private static final Pattern SET_XYZ_METHOD_NAME    = Pattern.compile("set([A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME        = Pattern.compile("add(?:Configured)?");
    private static final Pattern ADD_XYZ_METHOD_NAME    = Pattern.compile("add(?:Configured)?([A-Z]\\w*)");
    private static final Pattern CREATE_XYZ_METHOD_NAME = Pattern.compile("create([A-Z]\\w*)");

    private static final
    class AntTask {

        final String                name;
        final ClassDoc              classDoc;
        private List<AntAttribute>  attributes;
        private List<AntSubelement> subelements;

        public AntTask(String name, ClassDoc classDoc, List<AntAttribute> attributes, List<AntSubelement> subelements) {
            this.name        = name;
            this.classDoc    = classDoc;
            this.attributes  = attributes;
            this.subelements = subelements;
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
        if ("-html-output-directory".equals(option)) return 2;
        if ("-mediawiki-output-directory".equals(option)) return 2;
        if ("-link".equals(option)) return 2;
        if ("-linkoffline".equals(option)) return 3;

        return 0;
    }

    /**
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static boolean
    start(final RootDoc rootDoc) throws IOException, ParserConfigurationException, SAXException {

        File                                              antlibFile               = new File("antlib.xml");
        File                                              htmlOutputDirectory      = null;
        File                                              mediawikiOutputDirectory = null;
        final Map<String /*packageName*/, URL /*target*/> externalJavadocs         = new HashMap<String, URL>();

        for (String[] option : rootDoc.options()) {
            if ("-antlib-file".equals(option[0])) {
                antlibFile = new File(option[1]);
            } else
            if ("-html-output-directory".equals(option[0])) {
                htmlOutputDirectory = new File(option[1]);
            } else
            if ("-mediawiki-output-directory".equals(option[0])) {
                mediawikiOutputDirectory = new File(option[1]);
            } else
            if ("-link".equals(option[0])) {
                URL targetUrl      = new URL(option[1]);
                URL packageListUrl = new URL(targetUrl, "/package-list");

                readExternalJavadocs(targetUrl, packageListUrl, externalJavadocs, rootDoc);
            } else
            if ("-linkoffline".equals(option[0])) {
                URL targetUrl      = new URL(option[1]);
                URL packageListUrl = new URL(option[2]);

                readExternalJavadocs(targetUrl, packageListUrl, externalJavadocs, rootDoc);
            } else
            {

                // It is quite counterintuitive, but 'options()' returns ALL options, not only those which
                // qualified by 'optionLength()'.
                ;
            }
        }

        if (htmlOutputDirectory == null && mediawikiOutputDirectory == null) {
            rootDoc.printError(
                "Exactly one of '-html-output-directory' and '-mediawiki-output-directory' must be given"
            );
        }

        // Parse the given ANTLIB file; see
        // https://ant.apache.org/manual/Types/antlib.html
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder        documentBuilder        = documentBuilderFactory.newDocumentBuilder();
        Document               document               = documentBuilder.parse(antlibFile);

        document.getDocumentElement().normalize();

        final List<AntTask> tasks = new ArrayList<AntTask>();
        for (Element taskdefElement : AntDoclet.<Element>nl2i(document.getElementsByTagName("taskdef"))) {

            final String taskName = taskdefElement.getAttribute("name");
            if (taskName == null) {
                rootDoc.printError(antlibFile + ": Taskdef '" + taskdefElement + "' lacks the name");
                continue;
            }

            String taskClassname = taskdefElement.getAttribute("classname");
            if (taskClassname == null) {
                rootDoc.printError(antlibFile + ": Taskdef '" + taskdefElement + "' lacks the class name");
                continue;
            }

            final ClassDoc classDoc = Docs.findClass(rootDoc, taskClassname);
            if (classDoc == null) {
                rootDoc.printError("Class '" + taskClassname + "' not found for ANT task '" + taskName + "'");
                continue;
            }

            // Deduce attributes and subelements from the special methods that ANT uses.
            final List<AntAttribute>  attributes  = new ArrayList<AntAttribute>();
            final List<AntSubelement> subelements = new ArrayList<AntSubelement>();
            for (MethodDoc md : classDoc.methods()) {

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
                } else
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
                    && methodParameters.length == 1
                ) {
                    String name = CamelCase.toLowerCamelCase(m.group(1));
                    Type   type = methodParameters[0].type();

                    subelements.add(new AntSubelement(md, name, type));
                }
            }

            tasks.add(new AntTask(taskName, classDoc, attributes, subelements));
        }

        for (final AntTask task : tasks) {

            final Html html = new Html(new Html.ExternalJavadocsLinkMaker(externalJavadocs, new LinkMaker() {

                @Override public String
                makeHref(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

                    if (to instanceof ClassDoc) {
                        ClassDoc toClass = (ClassDoc) to;
                        for (AntTask t : tasks) {
                            if (toClass == t.classDoc) return t.name + ".html";
                        }

                        rootDoc.printError(from.position(), "'" + to + "' does not designate a task");
                        throw new Longjump();
                    }

                    if (to instanceof MethodDoc) {
                        MethodDoc toMethod = (MethodDoc) to;
                        ClassDoc  toClass = toMethod.containingClass();
                        for (AntTask t : tasks) {

                            if (t.classDoc != toClass) continue;

                            for (AntAttribute a : t.attributes) {
                                if (a.methodDoc == toMethod) {
                                    String fragment = '#' + a.name;
                                    return toMethod.containingClass() == task.classDoc ? fragment : task.name + fragment;
                                }
                            }
                            for (AntSubelement s : t.subelements) {
                                if (s.methodDoc == toMethod) {
                                    String fragment = '#' + (s.name != null ? s.name : s.type.asClassDoc().qualifiedName());
                                    return toMethod.containingClass() == task.classDoc ? fragment : task.name + fragment;
                                }
                            }

                            rootDoc.printError(from.position(), "'" + to + "' does not designate an attribute or subelement");
                            throw new Longjump();
                        }

                        rootDoc.printError(from.position(), "'" + toClass + "' is not an ANT task");
                        throw new Longjump();
                    }

                    rootDoc.printError(from.position(), "'" + to + "' does not designate a task, attribute or subelement");
                    throw new Longjump();
                }

                @Override public String
                makeDefaultLabel(Doc from, Doc to, RootDoc rootDoc) throws Longjump {

                    if (to instanceof ClassDoc) {
                        ClassDoc toClass = (ClassDoc) to;
                        for (AntTask t : tasks) {
                            if (toClass == t.classDoc) return "&lt;" + t.name + "&gt;";
                        }
                    }

                    if (to instanceof MethodDoc) {
                        MethodDoc toMethod = (MethodDoc) to;
                        for (AntTask t : tasks) {
                            for (AntAttribute a : t.attributes) {
                                if (a.methodDoc == toMethod) return a.name + "=\"...\"";
                            }
                            for (AntSubelement s : t.subelements) {
                                if (s.methodDoc == toMethod) return "&lt;" + s.name + "&gt;";
                            }
                        }
                    }

                    rootDoc.printError(from.position(), "'" + to + "' does not designate a task, attribute or subelement");
                    throw new Longjump();
                }
            }));

            // Produce HTML output iff requested.
            if (htmlOutputDirectory != null) {

                FileUtil.printToFile(
                    new File(htmlOutputDirectory, "/tasks/" + task.name + ".html"),
                    Charset.forName("ISO8859-1"),
                    new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                        @Override public void
                        consume(PrintWriter pw) {

                            String htmlText;
                            try {
                                htmlText = html.fromTags(task.classDoc.inlineTags(), task.classDoc, rootDoc);
                            } catch (Longjump e) {
                                return;
                            }

                            pw.println("<!DOCTYPE html>");
                            pw.println("  <html>");
                            pw.println("  <head>");
                            pw.println("    <title>Task &lt;" + task.name + "&gt;</title>");
                            pw.println("  </head>");
                            pw.println();
                            pw.println("  <body>");
                            pw.println("    <h1><code>&lt;" + task.name + "></code> Task</h1>");
                            pw.println();
                            pw.write(htmlText);

                            if (!task.attributes.isEmpty()) {
                                pw.println();
                                pw.println("    <h2>Attributes</h2>");
                                pw.println();
                                pw.println("    <p>Default values are <u>underlined</u>.</p>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntAttribute attribute : task.attributes) {

                                    String defaultValue;
                                    {
                                        Tag[] dvt = attribute.methodDoc.tags("@defaultValue");
                                        if (dvt.length == 0) {
                                            defaultValue = null;
                                        } else {
                                            if (dvt.length > 1) {
                                                rootDoc.printWarning("Only one '@defaultValue' tag allowed");
                                            }
                                            try {
                                                defaultValue = html.fromJavadocText(dvt[0].text(), attribute.methodDoc, rootDoc);
                                            } catch (Longjump e) {
                                                defaultValue = null;
                                            }
                                        }
                                    }

                                    // See http://ant.apache.org/manual/develop.html#set-magic
                                    String rhs;
                                    String attributeTypeHtmlText = null;

                                    Type   attributeType = attribute.type;
                                    String qualifiedAttributeTypeName = attributeType.qualifiedTypeName();
                                    if ("boolean".equals(qualifiedAttributeTypeName)) {
                                        if (Boolean.parseBoolean(defaultValue)) {
                                            rhs = "<u>true</u>|false";
                                        } else {
                                            rhs = "true|<u>false</u>";
                                        }
                                    } else
                                    if (attributeType.isPrimitive()) {
                                        rhs = "<var>N</var>";
                                        if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
                                    } else
                                    if (
                                        "java.io.File".equals(qualifiedAttributeTypeName)
                                        || "org.apache.tools.ant.types.Resource".equals(qualifiedAttributeTypeName)
                                        || "org.apache.tools.ant.types.Path".equals(qualifiedAttributeTypeName)
                                        || "java.lang.Class".equals(qualifiedAttributeTypeName)
                                        || "java.lang.String".equals(qualifiedAttributeTypeName)
                                    ) {
                                        rhs = "<var>" + CamelCase.toHyphenSeparated(attributeType.simpleTypeName()) + "</var>";
                                        if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
                                    } else
                                    if (attributeType instanceof Doc && ((Doc) attributeType).isEnum()) {
                                        StringBuilder sb = new StringBuilder();
                                        for (FieldDoc enumConstant : ((ClassDoc) attributeType).enumConstants()) {
                                            if (sb.length() > 0) sb.append('|');
                                            boolean isDefault = enumConstant.name().equals(defaultValue);
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
                                                    this.getSingleStringParameterConstructor((ClassDoc) attributeType, rootDoc),
                                                    CamelCase.toHyphenSeparated(attributeType.simpleTypeName()),
                                                    rootDoc
                                                )
                                                + "</var>"
                                            );
                                        } catch (Longjump l) {
                                            rhs = "<var>" + CamelCase.toHyphenSeparated(attributeType.simpleTypeName()) + "</var>";
                                        }
                                        if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
                                    }

                                    pw.println("      <dt>");
                                    pw.println("        <a name=\"" + attribute.name + "\" />");
                                    pw.println("        <code>" + attribute.name + "=\"" + rhs + "\"</code>");
                                    pw.println("      </dt>");

                                    // Generate attribute description.
                                    try {
                                        String attributeHtmlText = html.generateFor(attribute.methodDoc, attribute.methodDoc, rootDoc);

                                        pw.println("      <dd>");
                                        pw.println("        " + attributeHtmlText.replaceAll("\\s+", " "));
                                        if (attributeTypeHtmlText != null) {
                                            pw.println("        " + attributeTypeHtmlText.replaceAll("\\s+", " "));
                                        }
                                        pw.println("      </dd>");
                                    } catch (Longjump l) {}
                                }
                                pw.println("    </dl>");
                            }

                            if (!task.subelements.isEmpty()) {
                                pw.println();
                                pw.println("    <h2>Subelements</h2>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntSubelement subelement : task.subelements) {
                                    pw.println("      <dt>");
                                    if (subelement.name != null) {
                                        pw.println("        <a name=\"" + subelement.name + "\" />");
                                        pw.println("        <code>&lt;" + subelement.name + "></code>");
                                    } else {
                                        pw.println("        <a name=\"" + subelement.type.asClassDoc().qualifiedName() + "\" />");
                                        pw.println("        Any <code>" + subelement.type.asClassDoc().qualifiedName() + "</code>");
                                    }
                                    pw.println("      </dt>");

                                    // Generate subelement description.
                                    try {
                                        String subelementHtmlText = html.generateFor(subelement.methodDoc, subelement.methodDoc, rootDoc);
                                        pw.println("      <dd>");
                                        pw.println("        " + subelementHtmlText.replaceAll("\\s+", " "));
                                        pw.println("      </dd>");
                                    } catch (Longjump e) {}
                                }
                                pw.println("    </dl>");
                            }
                            pw.println("  </body>");
                            pw.println("</html>");
                        }

                        private ConstructorDoc
                        getSingleStringParameterConstructor(ClassDoc classDoc, DocErrorReporter errorReporter) throws Longjump {
                            for (ConstructorDoc cd : classDoc.constructors()) {
                                if (cd.parameters().length == 1 && "java.lang.String".equals(cd.parameters()[0].type().qualifiedTypeName())) {
                                    return cd;
                                }
                            }
                            errorReporter.printError(classDoc.position(), "Class has no single-string-parameter constructor");
                            throw new Longjump();
                        }
                    }
                );
            }

            // Produce WIKIMEDIA markup output iff requested.
            if (mediawikiOutputDirectory != null) {
                FileUtil.printToFile(
                    new File(mediawikiOutputDirectory, "/tasks/" + task.name + ".mw"),
                    Charset.forName("ISO8859-1"),
                    new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                        @Override public void
                        consume(PrintWriter pw) {

                            String htmlText;
                            try {
                                htmlText = html.fromTags(task.classDoc.inlineTags(), task.classDoc, rootDoc);
                            } catch (Longjump e) {
                                return;
                            }

                            pw.println("<div style=\"font-size:16pt\"><code>'''<" + task.name + ">'''</code></div>");
                            pw.println();
                            String mediawiki = Mediawiki.fromHtml(htmlText);
                            pw.println(mediawiki);

                            if (!task.attributes.isEmpty()) {
                                pw.println();
                                pw.println("== Attributes ==");
                                pw.println();
                                pw.println("Default values are <u>underlined</u>.");
                                pw.println();
                                for (AntAttribute attribute : task.attributes) {
                                    try {
                                        String attributeHtmlText = html.generateFor(attribute.methodDoc, task.classDoc, rootDoc);
                                        pw.println(
                                            ";<div id=\""
                                            + attribute.methodDoc
                                            + "\"></div><code>"
                                            + attribute.methodDoc
                                            + "=\"''text''\"</code>"
                                        );
                                        pw.println(':' + Mediawiki.fromHtml(attributeHtmlText.replaceAll("\\s+", " ")));
                                    } catch (Longjump e) {}
                                }
                            }

                            if (!task.subelements.isEmpty()) {
                                pw.println();
                                pw.println("== Subelements ==");
                                pw.println();
                                for (AntSubelement subelement : task.subelements) {
                                    try {
                                        String subelementHtmlText = html.generateFor(subelement.methodDoc, task.classDoc, rootDoc);
                                        pw.println(";<div id=\"" + subelement.name + "\"></div>" + subelement.name);
                                        pw.println(':' + Mediawiki.fromHtml(subelementHtmlText.replaceAll("\\s+", " ")));
                                    } catch (Longjump e) {}
                                }
                            }
                        }
                    }
                );
            }
        }

        if (document.getElementsByTagName("typedef").getLength() > 0) {
            rootDoc.printWarning("<typedef>s are not yet supported");
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

        return true;
    }

    private static void readExternalJavadocs(URL targetUrl, URL packageListUrl,
            final Map<String, URL> externalJavadocs, final RootDoc rootDoc) throws IOException {
        List<String> packageNames = LineUtil.readAllLines(
            new InputStreamReader(packageListUrl.openStream()),
            true
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
