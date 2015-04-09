
/*
 * de.unkrig.doclet.cs - A doclet which generates metadata documents for a CheckStyle extension
 *
 * Copyright (c) 2014, Arno Unkrig
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.RootDoc;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.Html;
import de.unkrig.commons.doclet.Mediawiki;
import de.unkrig.commons.file.FileUtil;
import de.unkrig.commons.io.LineUtil;
import de.unkrig.commons.lang.protocol.ConsumerWhichThrows;
import de.unkrig.commons.lang.protocol.Longjump;

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
        if ("-output-directory".equals(option)) return 2;
        if ("-linkoffline".equals(option)) return 3;

        return 0;
    }

    /**
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/javadoc/doclet/overview.html">"Doclet
     * Overview"</a>.
     */
    public static boolean
    start(final RootDoc rootDoc) throws IOException, ParserConfigurationException, SAXException {

        File                                              antlibFile       = new File("antlib.xml");
        File                                              outputDirectory  = new File(".");
        final Map<String /*packageName*/, URL /*target*/> externalJavadocs = new HashMap<String, URL>();

        for (String[] option : rootDoc.options()) {
            if ("-antlib-file".equals(option[0])) {
                antlibFile = new File(option[1]);
            } else
            if ("-output-directory".equals(option[0])) {
                outputDirectory = new File(option[1]);
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

        final Html html = new Html(externalJavadocs);

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder        documentBuilder        = documentBuilderFactory.newDocumentBuilder();
        Document               document               = documentBuilder.parse(antlibFile);

        document.getDocumentElement().normalize();

        for (Element taskdefElement : AntDoclet.<Element>nl2i(document.getElementsByTagName("taskdef"))) {

            String taskName = taskdefElement.getAttribute("name");
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

            FileUtil.printToFile(
                new File(outputDirectory, "/tasks/" + taskName + ".mw"),
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

                        String mediawiki = Mediawiki.fromHtml(htmlText);

                        pw.write(mediawiki);
                    }
                }
            );
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
