
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

// SUPPRESS CHECKSTYLE WrapMethod:9999

package de.unkrig.doclet.ant.templates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.ConstructorDoc;
import com.sun.javadoc.Doc;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.FieldDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;

import de.unkrig.commons.doclet.Docs;
import de.unkrig.commons.doclet.Types;
import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.commons.text.Notations;
import de.unkrig.commons.util.collections.IterableUtil.ElementWithContext;
import de.unkrig.doclet.ant.AntDoclet;
import de.unkrig.doclet.ant.AntDoclet.AntAttribute;
import de.unkrig.doclet.ant.AntDoclet.AntSubelement;
import de.unkrig.doclet.ant.AntDoclet.AntType;
import de.unkrig.doclet.ant.AntDoclet.AntTypeGroup;
import de.unkrig.notemplate.HtmlTemplate;
import de.unkrig.notemplate.javadocish.Options;
import de.unkrig.notemplate.javadocish.templates.AbstractDetailHtml;
import de.unkrig.notemplate.javadocish.templates.AbstractRightFrameHtml;

public
class TypeHtml extends AbstractDetailHtml {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    public void
    render(
        final AntTypeGroup                typeGroup,
        Collection<AntTypeGroup>          allTypeGroups,
        final ElementWithContext<AntType> atwc,
        final Html                        html,
        final RootDoc                     rootDoc,
        Options                           options
    ) {
        AntType antType = atwc.current();

        List<Section> sections = new ArrayList<>();

        MethodDoc characterData = antType.characterData;
        if (characterData != null) {

            String content;
            try {
                content = html.generateFor(characterData, rootDoc);
            } catch (Longjump l) {
                content = "???";
            }

            SectionAddendum sa = new SectionAddendum(
                "Text between start and end tag", // title
                content,                          // content
                null                              // anchor
            );

            Section textSection = new Section(
                "text", // anchor
                "Text", // navigationLinkLabel
                "Text", // summaryTitle1
                null,   // summaryTitle2
                null,   // summaryTableHeadings
                null,   // detailTitle
                null,   // detailDescription
                null    // summaryItemComparator
            );

            textSection.addenda.add(sa);

            sections.add(textSection);
        }

        ATTRIBUTES: {
            List<AntAttribute> attributes = antType.attributes;
            if (attributes.isEmpty()) break ATTRIBUTES;

            List<SectionItem> attributeSectionItems = new ArrayList<>();

            {
                Map<AntAttribute, Collection<AntAttribute>>
                seeSources = new HashMap<>();
                Map<AntAttribute, AntAttribute>
                seeTargets = new HashMap<>();

                for (AntAttribute a : attributes) {

                    AntAttribute seeTarget = TypeHtml.seeAttribute(a, attributes, rootDoc);

                    if (seeTarget != null) {

                        Collection<AntAttribute> sources = seeSources.get(seeTarget);
                        if (sources == null) {
                            seeSources.put(seeTarget, (sources = new ArrayList<>()));
                        }
                        sources.add(a);

                        seeTargets.put(a, seeTarget);
                    }
                }

                for (AntAttribute a : attributes) {
                    if (seeTargets.containsKey(a)) continue;
                    Collection<AntAttribute> sss = seeSources.get(a);
                    if (sss == null) sss = Collections.emptyList();

                    String summaryTitle = TypeHtml.attributeSummaryTitle(a);
                    for (AntAttribute sa : sss) {
                        summaryTitle += ", " + TypeHtml.attributeSummaryTitle(sa);
                    }

                    String detailTitle = TypeHtml.attributeTerm(a, html, rootDoc);
                    for (AntAttribute sa : sss) {
                        detailTitle += ", " + TypeHtml.attributeTerm(sa, html, rootDoc);
                    }

                    final String summaryDescription = html.summaryDescription(a.methodDoc, rootDoc);

                    String detailContent;
                    {
                        String tmp;
                        try {
                            tmp = html.generateFor(a.methodDoc, rootDoc);
                        } catch (Longjump l) {
                            tmp = "???";
                        }
                        detailContent = tmp;
                    }

                    SectionItem sectionItem = new SectionItem(
                        a.name + "_attribute",                             // anchor
                        new String[] { summaryTitle, summaryDescription }, // summaryTableCells
                        detailTitle,                                       // detailTitle
                        () -> { TypeHtml.this.p(detailContent); }          // printDetailContent
                    );

                    attributeSectionItems.add(sectionItem);
                }
            }

            Section attributesSection = new Section(
                "attributes",                            // anchor
                "Attributes",                            // navigationLinkLabel
                "Attribute Summary",                     // summaryTitle1
                "Attributes",                            // summaryTitle2
                new String[] { "Name", "Description" },  // summaryTableHeadings
                "Attribute Detail",                      // detailTitle
                "Default values are <u>underlined</u>.", // detailDescription
                null                                     // summaryItemComparator
            );

            attributesSection.items.addAll(attributeSectionItems);

            sections.add(attributesSection);
        }

        SUBELEMENTS: {
            List<AntSubelement> subelements = antType.subelements;
            if (subelements.isEmpty()) break SUBELEMENTS;

            List<SectionItem> subelementSectionItems = new ArrayList<>();

            for (AntSubelement subelement : subelements) {

                ClassDoc subelementTypeClassDoc = subelement.type.asClassDoc();
                String   stqn                   = subelementTypeClassDoc.qualifiedName();

                String anchor, summaryNameHtml;
                if (subelement.name != null) {
                    anchor          = subelement.name;
                    summaryNameHtml = "<code>&lt;" + subelement.name + "&gt;</code>";
                } else {
                    anchor = stqn;
                    try {
                        summaryNameHtml = "Any " + html.makeLink(
                            subelementTypeClassDoc, // from
                            subelementTypeClassDoc, // to
                            true,                   // plain
                            null,                   // label
                            null,                   // target
                            rootDoc                 // rootDoc
                        );
                    } catch (Longjump l) {
                        summaryNameHtml = "Any <code>" + stqn + "</code>";
                    }
                }

                String firstSentence;
                try {
                    firstSentence = html.fromTags(
                        subelement.methodDoc.firstSentenceTags(),
                        subelement.methodDoc,
                        rootDoc
                    );
                } catch (Longjump e) {
                    firstSentence = "???";
                }

                if (subelement.methodDoc.tags("@deprecated").length > 0) firstSentence = "(deprecated)";

                subelementSectionItems.add(new SectionItem(
                    anchor + "_subelement",                          // anchor
                    new String[] { summaryNameHtml, firstSentence }, // summaryTableCells
                    summaryNameHtml,                                 // detailTitle
                    () -> {                                          // printDetailContent
                        TypeHtml.this.printSubelement2(
                            atwc.current().classDoc, // from
                            subelement,              // subelement
                            html,                    // html
                            rootDoc,                 // rootDoc
                            new HashSet<ClassDoc>()  // seenTypes
                        );
                    }
                ));
            }

            Section subelementsSection = new Section(
                "subelements",                          // anchor
                "Subelements",                          // navigationLinkLabel
                "Subelement Summary",                   // summaryTitle1
                "Subelements",                          // summaryTitle2
                new String[] { "Name", "Description" }, // summaryTableHeadings
                "Subelement Detail",                    // detailTitle
                null,                                   // detailDescription
                null                                    // summaryItemComparator
            );

            subelementsSection.items.addAll(subelementSectionItems);

            sections.add(subelementsSection);
        }

        String home = "";

        // Compose the "nav1" from all the ant type groups (tasks, types, chainable readers, ...).
        List<String> nav1 = new ArrayList<>();
        nav1.add("Overview");
        nav1.add(home + "../overview-summary.html");
        for (AntTypeGroup atg : allTypeGroups) {
            if (atg.types.isEmpty()) continue;
            nav1.add(atg.name);
            nav1.add(atg == typeGroup ? AbstractRightFrameHtml.HIGHLIT : AbstractRightFrameHtml.DISABLED);
        }
        nav1.add("Index");
        nav1.add(home + (options.splitIndex ? "index-files/index-1.html" : "index-all.html"));

        super.rDetail(
            typeGroup.typeTitleMf.format(new String[] { antType.name }),   // windowTitle
            options,                                                       // options
            new String[] { "../stylesheet.css", "../stylesheet2.css" },    // stylesheetLinks
            nav1.toArray(new String[nav1.size()]),                         // nav1
            new String[] {                                                 // nav2
                TypeHtml.antTypeLink("Prev " + typeGroup.name, home, atwc.previous()),
                TypeHtml.antTypeLink("Next " + typeGroup.name, home, atwc.next()),
            },
            new String[] {                                                 // nav3
                "Frames",    home + "../index.html?" + typeGroup.subdir + "/" + antType.name + ".html",
                "No Frames", antType.name + ".html",
            },
            new String[] {                                                 // nav4
                "All Types", home + "../alldefinitions-noframe.html",
            },
            HtmlTemplate.esc(typeGroup.name),                              // subtitle
            typeGroup.typeHeadingMf.format(new String[] { antType.name }), // heading
            typeGroup.typeHeadingMf.format(new String[] { antType.name }), // headingTitle
            new Runnable() {                                               // prolog

                @Override public void
                run() {
                    try {
                        TypeHtml.this.p(html.generateFor(antType.classDoc, rootDoc));
                    } catch (Longjump l) {}
                }
            },
            sections                                                       // sections
        );
    }

    private static String
    antTypeLink(String labelHtml, String home, @Nullable AntType antType) {

        if (antType == null) return labelHtml;

        return (
            "<a href=\""
            + antType.name
            + ".html\"><span class=\"strong\">"
            + labelHtml
            + "</span></a>"
        );
    }

//    private void
//    printType(final AntType antType, final Html html, final RootDoc rootDoc, Set<ClassDoc> seenTypes)
//    throws Longjump {
//        this.p(html.fromTags(antType.classDoc.inlineTags(), antType.classDoc, rootDoc));
//
//        MethodDoc characterData = antType.characterData;
//        if (characterData != null) {
//            this.l(
//"",
//"    <h3>Text between start and end tag</h3>",
//""
//            );
//            this.printCharacterData(characterData, html, rootDoc);
//        }
//
//        if (!antType.attributes.isEmpty()) {
//            this.l(
//"",
//"    <h3>Attributes</h3>",
//"",
//"    <p>Default values are <u>underlined</u>.</p>",
//""
//            );
//            this.printAttributes(antType.attributes, html, rootDoc);
//        }
//
//        List<AntSubelement> subelements = antType.subelements;
//        if (!subelements.isEmpty()) {
//            this.l(
//"",
//"    <h3>Subelements</h3>",
//""
//            );
//            this.printSubelements(antType.classDoc, subelements, html, rootDoc, seenTypes);
//        }
//    }

    private void
    printSubelements(Doc from, List<AntSubelement> subelements, Html html, RootDoc rootDoc, Set<ClassDoc> seenTypes) {

        Map<String, List<AntSubelement>>
        subelementsByGroup = new LinkedHashMap<>();

        for (AntSubelement subelement : subelements) {

            String              group              = subelement.group;
            List<AntSubelement> subelementsOfGroup = subelementsByGroup.get(group);

            if (subelementsOfGroup == null) {
                subelementsOfGroup = new ArrayList<>();
                subelementsByGroup.put(group, subelementsOfGroup);
            }

            subelementsOfGroup.add(subelement);
        }

        this.l("    <dl class=\"ant-subelements\">");

        if (subelementsByGroup.size() == 1 && subelementsByGroup.containsKey(null)) {
            for (AntSubelement subelement : subelements) {
                this.printSubelement(from, subelement, html, rootDoc, seenTypes);
            }
        } else {
            for (Entry<String, List<AntSubelement>> e : subelementsByGroup.entrySet()) {
                String              group              = e.getKey();
                List<AntSubelement> subelementsOfGroup = e.getValue();

                this.l(
"      <h6>" + (group == null ? "Other" : group) + ":</h6>"
                );

                for (AntSubelement attribute : subelementsOfGroup) {
                    this.printSubelement(from, attribute, html, rootDoc, seenTypes);
                }
            }
        }

        this.l("    </dl>");
    }

    /**
     * Prints documentation for "character data" (nested text between opening and closing tags).
     */
    private void
    printCharacterData(
        MethodDoc     characterData,
        final Html    html,
        final RootDoc rootDoc
    ) {

        // See http://ant.apache.org/manual/develop.html#set-magic

        // Generate character data description.
        try {
            String attributeHtmlText = html.generateFor(characterData, rootDoc);

            this.l(
"    " + attributeHtmlText.replaceAll("\\s+", " ")
            );
        } catch (Longjump l) {}
    }

    private void
    printAttribute(
        AntAttribute             primaryAttribute,
        Collection<AntAttribute> alternativeAttributes,
        final Html               html,
        final RootDoc            rootDoc
    ) {

        this.l(
"      <dt class=\"ant-attribute-term\">" + TypeHtml.attributeTerm(primaryAttribute, html, rootDoc) + "</dt>"
        );

        for (AntAttribute a : alternativeAttributes) {
            this.l(
"      <dt class=\"ant-attribute-term\">" + TypeHtml.attributeTerm(a, html, rootDoc) + "</dt>"
            );
        }

        // Generate attribute description.
        try {
            String
            attributeHtmlText = html.generateFor(primaryAttribute.methodDoc, rootDoc);

            this.l(
"      <dd class=\"ant-attribute-definition\">",
"        " + attributeHtmlText.replaceAll("\\s+", " "),
"      </dd>"
            );
        } catch (Longjump l) {}
    }

    private static String
    attributeSummaryTitle(AntAttribute attribute) {
        return "<code>" + attribute.name + "=\"...\"</code>";
    }

    private static String
    attributeTerm(AntAttribute attribute, Html html, RootDoc rootDoc) {

        String defaultValueHtmlText = TypeHtml.getTagOfDoc(
            attribute.methodDoc,
            "@ant.defaultValue",
            html,
            rootDoc
        );

        if (defaultValueHtmlText != null) {

            // Display control characters as "&#xxx;".
            for (int i = 0; i < defaultValueHtmlText.length(); i++) {
                char c = defaultValueHtmlText.charAt(i);
                if (c < ' ') {
                    defaultValueHtmlText = (
                        defaultValueHtmlText.substring(0, i)
                        + "&amp;#"
                        + (int) c
                        + ';'
                        + defaultValueHtmlText.substring(i + 1)
                    );
                }
            }
        }

        // Non-plain links in the tag argument contain "<code>...</code>" which we don't want
        // e.g. when comparing against enum constants.
        String defaultValue = (
            defaultValueHtmlText == null
            ? null
            : defaultValueHtmlText.replaceAll("</?code>", "")
        );

        String valueExplanationHtml = TypeHtml.getTagOfDoc(
            attribute.methodDoc,
            "@ant.valueExplanation",
            html,
            rootDoc
        );

        // See http://ant.apache.org/manual/develop.html#set-magic
        String rhs;

        Type   attributeType                = attribute.type;
        String qualifiedAttributeTypeName   = attributeType.qualifiedTypeName();
        String attributeSetterParameterName = attribute.methodDoc.parameters()[0].name();

        final ClassDoc enumeratedAttributeClassdoc = rootDoc.classNamed(
            "org.apache.tools.ant.types.EnumeratedAttribute"
        );

        if (valueExplanationHtml != null) {
            rhs = valueExplanationHtml;
            if (defaultValue != null) {
                rhs += "|<u>" + defaultValueHtmlText + "</u>";
            }
        } else
        if (
            "boolean".equals(qualifiedAttributeTypeName)
            || "java.lang.Boolean".equals(qualifiedAttributeTypeName)
        ) {
            if ("false".equals(defaultValue) || defaultValue == null) {
                rhs = "true|<u>false</u>";
            } else
            if ("true".equals(defaultValue)) {
                rhs = "<u>true</u>|false";
            } else
            {
                rhs = "true|false";
                rootDoc.printWarning(
                    attribute.methodDoc.position(),
                    "Invalid default value \"" + defaultValue + "\" for boolean attribute"
                );
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
            rhs = "<var>" + Notations.fromCamelCase(attributeSetterParameterName).toLowerCaseHyphenated() + "</var>";
            if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
        } else
        if (
            "org.apache.tools.ant.types.Resource".equals(qualifiedAttributeTypeName)
            || "org.apache.tools.ant.types.Path".equals(qualifiedAttributeTypeName)
            || "java.lang.Class".equals(qualifiedAttributeTypeName)
            || "java.lang.Object".equals(qualifiedAttributeTypeName)
            || "de.unkrig.antcontrib.util.Regex".equals(qualifiedAttributeTypeName)
        ) {
            rhs = "<var>" + Notations.fromCamelCase(attributeType.simpleTypeName()).toLowerCaseHyphenated() + "</var>";
            if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
        } else
        if (
            "java.io.File".equals(qualifiedAttributeTypeName)
            || "java.lang.String".equals(qualifiedAttributeTypeName)
        ) {
            rhs = "<var>" + Notations.fromCamelCase(attributeSetterParameterName).toLowerCaseHyphenated() + "</var>";
            if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
        } else
        if (attributeType instanceof Doc && ((Doc) attributeType).isEnum()) {
            StringBuilder sb = new StringBuilder();

            boolean hadDefault = false;
            for (FieldDoc enumConstant : ((ClassDoc) attributeType).enumConstants()) {
                if (sb.length() > 0) sb.append('|');
                if (enumConstant.name().equals(defaultValue)) {
                    sb.append("<u>").append(enumConstant.name()).append("</u>");
                    hadDefault = true;
                } else {
                    sb.append(enumConstant.name());
                }
            }
            if (defaultValue != null && !hadDefault) {
                rootDoc.printWarning(
                    attribute.methodDoc.position(),
                    "Default value \"" + defaultValue + "\" matches none of the enum constants"
                );
            }
            rhs = sb.toString();
        } else
        if (
            enumeratedAttributeClassdoc != null
            && attributeType instanceof ClassDoc
            && ((ClassDoc) attributeType).subclassOf(enumeratedAttributeClassdoc)
        ) {
            String attributeClassName = Types.className((ClassDoc) attributeType);

            String[] values;
            try {
                Class<?> clasS = Class.forName(attributeClassName);

                try {
                    values = (String[]) clasS.getMethod("getValues").invoke(clasS.newInstance());
                    StringBuilder sb = new StringBuilder();

                    boolean hadDefault = false;
                    for (String value : values) {
                        if (sb.length() > 0) sb.append('|');
                        if (value.equals(defaultValue)) {
                            sb.append("<u>").append(value).append("</u>");
                            hadDefault = true;
                        } else {
                            sb.append(value);
                        }
                    }
                    if (defaultValue != null && !hadDefault) {
                        rootDoc.printWarning(
                            attribute.methodDoc.position(),
                            "Default value \"" + defaultValue + "\" matches none of the enumerated attribute values"
                        );
                    }
                    rhs = sb.toString();
                } catch (Exception e) {
                    rootDoc.printError(
                        attribute.methodDoc.position(),
                        "Retrieving values of enumerated attribute type \"" + attributeClassName + "\": " + e.toString()
                    );
                    rhs = "???";
                }
            } catch (Exception | NoClassDefFoundError e) {

                Throwable t = e;
                for (Throwable t2 = t.getCause(); t2 != null; t2 = (t = t2).getCause());

                rootDoc.printError(
                    attribute.methodDoc.position(),
                    (
                        "Loading enumerated attribute type \""
                        + attributeClassName
                        + "\": "
                        + t.toString()
                        + ": Make sure that \"ant.jar\" and class \""
                        + attributeClassName
                        + "\" are on the doclet's classpath"
                    )
                );
                rhs = "???";
            }
        } else
        if (attributeType instanceof ClassDoc) {
            try {
                rhs = (
                    "<var>"
                    + html.makeLink(
                        attribute.methodDoc,                                                             // from
                        TypeHtml.getSingleStringParameterConstructor(                                    // to
                            (ClassDoc) attributeType,
                            attribute.methodDoc,
                            rootDoc
                        ),
                        true,                                                                            // plain
                        Notations.fromCamelCase(attributeType.simpleTypeName()).toLowerCaseHyphenated(), // label
                        null,                                                                            // target
                        rootDoc                                                                          // rootDoc
                    )
                    + "</var>"
                );
            } catch (Longjump l) {
                rhs = (
                    "<var>"
                    + Notations.fromCamelCase(attributeType.simpleTypeName()).toLowerCaseHyphenated()
                    + "</var>"
                );
            }

            if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
        } else
        {
            rhs = (
                "<var>"
                + Notations.fromCamelCase(attributeType.simpleTypeName()).toLowerCaseHyphenated()
                + "</var>"
            );
            if (defaultValue != null) rhs += "|<u>" + defaultValue + "</u>";
        }

        boolean mandatory = TypeHtml.docHasTag(attribute.methodDoc, "@ant.mandatory", rootDoc);

        if (mandatory && defaultValue != null) {
            rootDoc.printWarning(
                "\"@ant.mandatory\" together with \"@ant.defaultValue\" does not make much sense"
            );
        }

        return (
            "<a name=\""
            + attribute.methodDoc.containingClass().qualifiedName()
            + "/"
            + attribute.methodDoc.name()
            + "\" /><code>"
            + attribute.name
            + "=\""
            + rhs
            + "\"</code>"
            + (mandatory ? " (mandatory)" : "")
        );
    }

    private void
    printSubelement(
        Doc           from,
        AntSubelement subelement,
        final Html    html,
        final RootDoc rootDoc,
        Set<ClassDoc> seenTypes
    ) {
        ClassDoc subelementTypeClassDoc = subelement.type.asClassDoc();
        String   stqn                   = subelementTypeClassDoc.qualifiedName();

        this.l(
"      <dt class=\"ant-subelement-term\">"
        );

        if (subelement.name != null) {
            this.l(
"        <a name=\"&lt;" + subelement.name + "&gt;\" />",
"        <code>&lt;" + subelement.name + "></code>"
            );
        } else {
            this.l(
"        <a name=\"" + stqn + "\" />"
            );
            try {
                this.l(
"        Any <code>" + html.makeLink(
    from,                   // from
    subelementTypeClassDoc, // to
    false,                  // plain
    null,                   // label
    null,                   // target
    rootDoc
) + "</code>"
                );
            } catch (Longjump l) {
                this.l(
"        Any <code>" + stqn + "</code>"
                );
            }
        }

        this.l(
"      </dt>"
        );

        this.l(
"      <dd class=\"ant-subelement-definition\">"
        );

        this.printSubelement2(from, subelement, html, rootDoc, seenTypes);

        this.l(
"      </dd>"
        );
    }

    private void
    printSubelement2(
        Doc           from,
        AntSubelement subelement,
        final Html    html,
        final RootDoc rootDoc,
        Set<ClassDoc> seenTypes
    ) {

        // Generate subelement description.
        try {
            String subelementHtmlText = html.generateFor(
                subelement.methodDoc,
                rootDoc
            );
            this.l(
"        " + subelementHtmlText.replaceAll("\\s+", " ")
            );
        } catch (Longjump e) {}

        ClassDoc subelementTypeClassDoc = subelement.type.asClassDoc();
        String   stqn                   = subelementTypeClassDoc.qualifiedName();

        // Generate subelement type description.
        if (subelementTypeClassDoc.isIncluded()) {

            if (!seenTypes.add(subelementTypeClassDoc)) {
                this.l(
"        <br />",
"        (The configuration options for this element are the same <a href=\"#" + stqn + "\">as described above</a>.)"
                );
                return;
            }

            this.l(
"        <a name=\"" + stqn + "\" />"
            );
            try {
                String subelementTypeHtmlText = html.fromTags(
                    subelementTypeClassDoc.inlineTags(),
                    subelementTypeClassDoc,
                    rootDoc
                );
                this.l(
"        " + subelementTypeHtmlText.replaceAll("\\s+", " ")
                );
            } catch (Longjump l) {}

            // Subelement's character data.
            MethodDoc
            characterData = AntDoclet.characterDataOf(subelementTypeClassDoc);
            if (characterData != null) {
                this.l(
"        <h5>Text between start and end tag:</h5>"
                );
                this.printCharacterData(characterData, html, rootDoc);
            }

            // Subelement's attributes' descriptions.
            List<AntAttribute>
            subelementAttributes = AntDoclet.attributesOf(subelementTypeClassDoc, rootDoc);
            if (!subelementAttributes.isEmpty()) {
                this.l(
"        <h5>Attributes:</h5>"
                );
                this.printAttributes(subelementAttributes, html, rootDoc);
            }

            // Subelement's subelements' descriptions.
            List<AntSubelement>
            subelementSubelements = AntDoclet.subelementsOf(subelementTypeClassDoc, rootDoc);
            if (!subelementSubelements.isEmpty()) {
                this.l(
"        <h5>Subelements:</h5>"
                );
                this.printSubelements(from, subelementSubelements, html, rootDoc, seenTypes);
            }

            seenTypes.remove(subelementTypeClassDoc);
        }
    }

    private void
    printAttributes(List<AntAttribute> attributes, Html html, RootDoc rootDoc) {

        Map<String, List<AntAttribute>>
        attributesByGroup = new LinkedHashMap<>();

        for (AntAttribute attribute : attributes) {

            String             group             = attribute.group;
            List<AntAttribute> attributesOfGroup = attributesByGroup.get(group);

            if (attributesOfGroup == null) {
                attributesOfGroup = new ArrayList<>();
                attributesByGroup.put(group, attributesOfGroup);
            }
            attributesOfGroup.add(attribute);
        }

        if (attributesByGroup.size() == 1 && attributesByGroup.containsKey(null)) {
            this.l(
"  <dl class=\"ant-attributes\">"
            );
            this.printAttributes2(attributes, html, rootDoc);
            this.l(
"  </dl>"
            );
        } else {
            for (Entry<String, List<AntAttribute>> e : attributesByGroup.entrySet()) {
                String             group             = e.getKey();
                List<AntAttribute> attributesOfGroup = e.getValue();

                this.l(
"  <h6>" + (group == null ? "Other" : group) + ":</h6>"
                );

                this.l(
"  <dl class=\"ant-attributes\">"
                );
                this.printAttributes2(attributesOfGroup, html, rootDoc);
                this.l(
"  </dl>"
                );
            }
        }
    }

    private void
    printAttributes2(List<AntAttribute> attributes, Html html, RootDoc rootDoc) {

        Map<AntAttribute, Collection<AntAttribute>>
        seeSources = new HashMap<>();
        Map<AntAttribute, AntAttribute>
        seeTargets = new HashMap<>();

        for (AntAttribute a : attributes) {

            AntAttribute seeTarget = TypeHtml.seeAttribute(a, attributes, rootDoc);

            if (seeTarget != null) {

                Collection<AntAttribute> sources = seeSources.get(seeTarget);
                if (sources == null) {
                    seeSources.put(seeTarget, (sources = new ArrayList<>()));
                }
                sources.add(a);

                seeTargets.put(a, seeTarget);
            }
        }

        for (AntAttribute a : attributes) {
            if (seeTargets.containsKey(a)) continue;
            Collection<AntAttribute> sss = seeSources.get(a);
            if (sss == null) {
                this.printAttribute(a, Collections.emptyList(), html, rootDoc);
            } else {
                this.printAttribute(a, sss, html, rootDoc);
            }
        }
    }

    /**
     * @return The ANT attribute designated by the "&#64;see" target iff the <var>attribute</var> (A) has NO text and
     *         (B) has a single block tag, "&#64;see", pointing to another attribute contained in
     *         <var>allAttributes</var>
     */
    @Nullable private static AntAttribute
    seeAttribute(AntAttribute attribute, List<AntAttribute> allAttributes, RootDoc rootDoc) {

        if (attribute.methodDoc.inlineTags().length == 0 && attribute.methodDoc.tags().length == 1) {

            Tag t = attribute.methodDoc.tags()[0];
            if ("@see".equals(t.name())) {
                Doc targetDoc;
                try {
                    targetDoc = Docs.findDoc(attribute.methodDoc, t.text(), rootDoc);
                } catch (Longjump e) {
                    targetDoc = null;
                }
                if (targetDoc != null) {
                    for (AntAttribute a : allAttributes) {
                        if (a.methodDoc == targetDoc) return a;
                    }
                }
            }
        }

        return null;
    }

    /**
     * @param kind E.g. {@code "@foo"}
     * @return     The argument of the tag, with all inline tags expanded, or {@code null} iff the <var>doc</var> does
     *             not have the tag
     */
    @Nullable private static String
    getTagOfDoc(Doc doc, String kind, final Html html, final RootDoc rootDoc) {

        Tag[] tags = doc.tags(kind);
        if (tags.length == 0) return null;

        if (tags.length > 1) {
            rootDoc.printWarning("At most one '" + kind + "' tag allowed");
        }

        try {
            return html.fromTags(tags[0].inlineTags(), doc, rootDoc);
        } catch (Longjump e) {
            return null;
        }
    }

    private static ConstructorDoc
    getSingleStringParameterConstructor(ClassDoc classDoc, Doc ref, DocErrorReporter errorReporter) throws Longjump {

        // NOTICE: ANT unfortunately does NOT support "singo-CharSequence-parameter" constructors.

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

    private static boolean
    docHasTag(Doc doc, String kind, RootDoc rootDoc) {

        Tag[] tags = doc.tags(kind);

        if (tags.length > 1) {
            rootDoc.printWarning("At most one '" + kind + "' tag allowed");
        }

        return tags.length > 0;
    }
}
