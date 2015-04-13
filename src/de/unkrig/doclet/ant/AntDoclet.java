
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
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.Parameter;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.Html;
import de.unkrig.commons.doclet.Mediawiki;
import de.unkrig.commons.file.FileUtil;
import de.unkrig.commons.io.LineUtil;
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

    private static final Pattern SET_XYZ_METHOD_NAME    = Pattern.compile("set([A-Z]\\w*)");
    private static final Pattern ADD_METHOD_NAME        = Pattern.compile("add(?:Configured)?");
    private static final Pattern ADD_XYZ_METHOD_NAME    = Pattern.compile("add(?:Configured)?([A-Z]\\w*)");
    private static final Pattern CREATE_XYZ_METHOD_NAME = Pattern.compile("create([A-Z]\\w*)");

    private static final
    class AntAttribute {

        final String           name;
        final String           typeName;
        final String           htmlText;
        @Nullable final String defaultValue;

        public
        AntAttribute(String name, String typeName, String htmlText, @Nullable String defaultValue) {
            this.name         = name;
            this.typeName     = typeName;
            this.htmlText     = htmlText;
            this.defaultValue = defaultValue;
        }
    }

    private static final
    class AntSubelement {

        @Nullable final String name;
        final String           typeName;
        final String           htmlText;

        public
        AntSubelement(@Nullable String name, String typeName, String htmlText) {
            this.name     = name;
            this.typeName = typeName;
            this.htmlText = htmlText;
        }
    }

    /**
     * Doclets are never instantiated.
     */
    private AntDoclet() {}

    /**
     * See <a href="https://docs.oracle.com/javase/6/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static int
    optionLength(String option) {

        if ("-antlib-file".equals(option)) return 2;
        if ("-html-output-directory".equals(option)) return 2;
        if ("-mediawiki-output-directory".equals(option)) return 2;
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
            if ("-linkoffline".equals(option[0])) {
                URL targetUrl      = new URL(option[1]);
                URL packageListUrl = new URL(option[2]);

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

        final Html html = new Html(externalJavadocs);

        // Parse the given ANTLIB file; see
        // https://ant.apache.org/manual/Types/antlib.html
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder        documentBuilder        = documentBuilderFactory.newDocumentBuilder();
        Document               document               = documentBuilder.parse(antlibFile);

        document.getDocumentElement().normalize();

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

                try {

                    String text = html.fromTags(md.inlineTags(), classDoc, rootDoc);
                    {
                        Tag[] seeTags = md.tags("@see");
                        if (seeTags.length > 0) {
                            StringBuilder sb = new StringBuilder(text);
                            sb.append("<dl><dt>See also:</dt>");
                            for (Tag seeTag : seeTags) {
                                sb.append("<dd>");
                                sb.append(html.resolveTarget(seeTag.text(), null, md, rootDoc));
                                sb.append("</dd>");
                            }
                            sb.append("</dl>");

                            text = sb.toString();
                        }
                    }

                    Matcher m;
                    if (
                        (m = SET_XYZ_METHOD_NAME.matcher(methodName)).matches()
                        && methodParameters.length == 1
                    ) {
                        String name     = CamelCase.toLowerCamelCase(m.group(1));
                        String typeName = methodParameters[0].typeName();
                        String defaultValue;

                        Tag[] dvt = md.tags("@defaultValue");
                        if (dvt.length == 0) {
                            defaultValue = null;
                        } else {
                            if (dvt.length > 1) {
                                rootDoc.printWarning("Only one '@defaultValue' tag is allowed");
                            }
                            defaultValue = html.fromJavadocText(dvt[0].text(), md, rootDoc);
                        }

                        attributes.add(new AntAttribute(
                            name,
                            typeName,
                            text,
                            defaultValue
                        ));
                    } else
                    if (
                        (m = CREATE_XYZ_METHOD_NAME.matcher(methodName)).matches()
                        && methodParameters.length == 0
                    ) {
                        String typeName = md.returnType().toString();//CamelCase.toLowerCamelCase(m.group(1));

                        subelements.add(new AntSubelement(
                            null, // name
                            typeName,
                            text
                        ));
                    } else
                    if (
                        (m = ADD_XYZ_METHOD_NAME.matcher(methodName)).matches()
                        && methodParameters.length == 1
                    ) {
                        String name     = CamelCase.toLowerCamelCase(m.group(1));
                        String typeName = methodParameters[0].typeName();

                        subelements.add(new AntSubelement(
                            name,
                            typeName,
                            text
                        ));
                    } else
                    if (
                        (m = ADD_METHOD_NAME.matcher(methodName)).matches()
                        && methodParameters.length == 1
                    ) {
                        String typeName = methodParameters[0].typeName();

                        subelements.add(new AntSubelement(
                            null, // name
                            typeName,
                            text
                        ));
                    }
                } catch (Longjump l) {}
            }

            // Produce HTML output iff requested.
            if (htmlOutputDirectory != null) {
                FileUtil.printToFile(
                    new File(htmlOutputDirectory, "/tasks/" + taskName + ".html"),
                    Charset.forName("ISO8859-1"),
                    new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                        @Override public void
                        consume(PrintWriter pw) {

                            String htmlText;
                            try {
                                htmlText = html.fromTags(classDoc.inlineTags(), classDoc, rootDoc);
                            } catch (Longjump e) {
                                return;
                            }

                            pw.println("<!DOCTYPE html>");
                            pw.println("  <html>");
                            pw.println("  <head>");
                            pw.println("    <title>Task &lt;" + taskName + "&gt;</title>");
                            pw.println("  </head>");
                            pw.println();
                            pw.println("  <body>");
                            pw.println("    <h1><code>&lt;" + taskName + "></code> Task</h1>");
                            pw.println();
                            pw.write(htmlText);

                            if (!attributes.isEmpty()) {
                                pw.println();
                                pw.println("    <h2>Attributes</h2>");
                                pw.println();
                                pw.println("    <p>Default values are <u>underlined</u>.</p>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntAttribute attribute : attributes) {
                                    String rhs;
                                    if ("boolean".equals(attribute.typeName)) {
                                        rhs = (
                                            Boolean.parseBoolean(attribute.defaultValue)
                                            ? "<u>true</u>|false"
                                            : "true|<u>false</u>"
                                        );
                                    } else
                                    if (
                                        "short".equals(attribute.typeName)
                                        || "int".equals(attribute.typeName)
                                        || "long".equals(attribute.typeName)
                                        || "float".equals(attribute.typeName)
                                        || "double".equals(attribute.typeName)
                                    ) {
                                        rhs = "<i>N</i>";
                                        if (attribute.defaultValue != null) {
                                            rhs += "|<u>" + attribute.defaultValue + "</u>";
                                        }
                                    } else
                                    if ("char".equals(attribute.typeName)) {
                                        rhs = "<i>character</i>";
                                        if (attribute.defaultValue != null) {
                                            rhs += "|<u>" + attribute.defaultValue + "</u>";
                                        }
                                    } else
                                    if ("String".equals(attribute.typeName)) {
                                        rhs = "<i>string</i>";
                                        if (attribute.defaultValue != null) {
                                            rhs += "|<u>" + attribute.defaultValue + "</u>";
                                        }
                                    } else
                                    {
                                        rhs = "<i>" + CamelCase.toHyphenSeparated(attribute.typeName) + "</i>";
                                        if (attribute.defaultValue != null) {
                                            rhs += "|<u>" + attribute.defaultValue + "</u>";
                                        }
                                    }
                                    pw.println("      <dt>");
                                    pw.println("        <a name=\"" + attribute.name + "\" />");
                                    pw.println("        <code>" + attribute.name + "=\"" + rhs + "\"</code>");
                                    pw.println("      </dt>");
                                    pw.println("      <dd>");
                                    pw.println("        " + attribute.htmlText.replaceAll("\\s+", " "));
                                    pw.println("      </dd>");
                                }
                                pw.println("    </dl>");
                            }

                            if (!subelements.isEmpty()) {
                                pw.println();
                                pw.println("    <h2>Subelements</h2>");
                                pw.println();
                                pw.println("    <dl>");
                                for (AntSubelement subelement : subelements) {
                                    pw.println("      <dt>");
                                    if (subelement.name != null) {
                                        pw.println("        <a name=\"" + subelement.name + "\" />");
                                        pw.println("        <code>&lt;" + subelement.name + "></code>");
                                    } else {
                                        pw.println("        <a name=\"" + subelement.typeName + "\" />");
                                        pw.println("        Any <code>" + subelement.typeName + "</code>");
                                    }
                                    pw.println("      </dt>");
                                    pw.println("      <dd>");
                                    pw.println("        " + subelement.htmlText.replaceAll("\\s+", " "));
                                    pw.println("      </dd>");
                                }
                                pw.println("    </dl>");
                            }
                            pw.println("  </body>");
                            pw.println("</html>");
                        }
                    }
                );
            }

            // Produce WIKIMEDIA markup output iff requested.
            if (mediawikiOutputDirectory != null) {
                FileUtil.printToFile(
                    new File(mediawikiOutputDirectory, "/tasks/" + taskName + ".mw"),
                    Charset.forName("ISO8859-1"),
                    new ConsumerWhichThrows<PrintWriter, RuntimeException>() {

                        @Override public void
                        consume(PrintWriter pw) {

                            String htmlText;
                            try {
                                htmlText = html.fromTags(classDoc.inlineTags(), classDoc, rootDoc);
                            } catch (Longjump e) {
                                return;
                            }

                            pw.println("<div style=\"font-size:16pt\"><code>'''<" + taskName + ">'''</code></div>");
                            pw.println();
                            String mediawiki = Mediawiki.fromHtml(htmlText);
                            pw.println(mediawiki);

                            if (!attributes.isEmpty()) {
                                pw.println();
                                pw.println("== Attributes ==");
                                pw.println();
                                pw.println("Default values are <u>underlined</u>.");
                                pw.println();
                                for (AntAttribute attribute : attributes) {
                                    pw.println(
                                        ";<div id=\""
                                        + attribute.name
                                        + "\"></div><code>"
                                        + attribute.name
                                        + "=\"''text''\"</code>"
                                    );
                                    pw.println(':' + Mediawiki.fromHtml(attribute.htmlText.replaceAll("\\s+", " ")));
                                }
                            }

                            if (!subelements.isEmpty()) {
                                pw.println();
                                pw.println("== Subelements ==");
                                pw.println();
                                for (AntSubelement subelement : subelements) {
                                    pw.println(";<div id=\"" + subelement.name + "\"></div>" + subelement.name);
                                    pw.println(':' + Mediawiki.fromHtml(subelement.htmlText.replaceAll("\\s+", " ")));

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
