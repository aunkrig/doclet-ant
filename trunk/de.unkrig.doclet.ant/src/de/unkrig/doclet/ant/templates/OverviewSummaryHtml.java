
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

package de.unkrig.doclet.ant.templates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.sun.javadoc.RootDoc;

import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.lang.AssertionUtil;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.doclet.ant.AntDoclet.AntType;
import de.unkrig.doclet.ant.AntDoclet.AntTypeGroup;
import de.unkrig.notemplate.javadocish.Options;
import de.unkrig.notemplate.javadocish.templates.AbstractRightFrameHtml;
import de.unkrig.notemplate.javadocish.templates.AbstractSummaryHtml;

public
class OverviewSummaryHtml extends AbstractSummaryHtml {

    static { AssertionUtil.enableAssertionsForThisClass(); }

    public void
    render(
        final Collection<AntTypeGroup> antTypeGroups,
        final RootDoc                  rootDoc,
        final Options                  options,
        final Html                     html
    ) {

        List<Section> sections = new ArrayList<>();
        for (AntTypeGroup typeGroup : antTypeGroups) {

            if (typeGroup.types.isEmpty()) continue;

            Section section = new Section();

            section.anchor             = typeGroup.subdir;
            section.title              = typeGroup.heading + " summary";
            section.firstColumnHeading = typeGroup.name;

            for (final AntType antType : typeGroup.types) {

                try {
                    SectionItem item = new SectionItem();

                    item.link    = typeGroup.subdir + "/" + antType.name + ".html";
                    item.name    = "<code>&lt;" + antType.name + "&gt;</code>";
                    item.summary = html.fromTags(
                        antType.classDoc.firstSentenceTags(), // tags
                        antType.classDoc,                     // ref
                        rootDoc                               // rootDoc
                    );

                    section.items.add(item);
                } catch (Longjump l) {}
            }

            sections.add(section);
        }

        super.rSummary(
            "Overview",                        // windowTitle
            options,                           // options
            new String[] { "stylesheet.css" }, // stylesheetLinks
            new String[] {                     // nav1
                "Overview",            AbstractRightFrameHtml.HIGHLIT,
                "Task",                AbstractRightFrameHtml.DISABLED,
                "Resource collection", AbstractRightFrameHtml.DISABLED,
                "Chainable reader",    AbstractRightFrameHtml.DISABLED,
                "Condition",           AbstractRightFrameHtml.DISABLED,
                "Index",               "index-all.html",
            },
            new String[] { "Prev", "Next" },   // nav2
            new String[] {                     // nav3
                "Frames",    "index.html",
                "No Frames", "overview-summary.html",
            },
            new String[] {                     // nav4
                "All Classes", "alldefinitions-noframe.html",
            },
            () -> {                            // prolog
                OverviewSummaryHtml.this.l(
"      <h1>ANT Library Overview</h1>"
                );
            },
            () -> {},                          // epilog
            sections                           // sections
        );
    }
}
