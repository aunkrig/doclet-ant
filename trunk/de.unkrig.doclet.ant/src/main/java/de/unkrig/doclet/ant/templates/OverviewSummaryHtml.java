
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

        final String overviewFirstSentenceHtml = AssertionUtil.notNull(Longjump.catchLongjump(
            () -> html.fromTags(rootDoc.firstSentenceTags(), rootDoc, rootDoc),
            ""
        ));

        final String overviewHtml = AssertionUtil.notNull(Longjump.catchLongjump(
            () -> html.fromTags(rootDoc.inlineTags(), rootDoc, rootDoc),
            ""
        ));

        List<Section> sections = new ArrayList<>();
        for (AntTypeGroup typeGroup : antTypeGroups) {

            if (typeGroup.types.isEmpty()) continue;

            Section section = new Section(
                typeGroup.subdir,               // anchor
                typeGroup.heading + " summary", // title
                null,                           // summary
                typeGroup.name                  // firstColumnHeading
            );

            for (final AntType antType : typeGroup.types) {

                try {
                    section.items.add(new SectionItem(
                        typeGroup.subdir + "/" + antType.name + ".html", // link
                        "<code>&lt;" + antType.name + "&gt;</code>",     // name
                        html.fromTags(                                   // summary
                            antType.classDoc.firstSentenceTags(), // tags
                            antType.classDoc,                     // ref
                            rootDoc                               // rootDoc
                        )
                    ));
                } catch (Longjump l) {}
            }

            sections.add(section);
        }

        super.rSummary(
            "Overview",                             // windowTitle
            options,                                // options
            new String[] { "stylesheet.css" },      // stylesheetLinks
            new String[] {                          // nav1
                "Overview",            AbstractRightFrameHtml.HIGHLIT,
                "Task",                AbstractRightFrameHtml.DISABLED,
                "Resource collection", AbstractRightFrameHtml.DISABLED,
                "Chainable reader",    AbstractRightFrameHtml.DISABLED,
                "Condition",           AbstractRightFrameHtml.DISABLED,
                "Index",               options.splitIndex ? "index-files/index-1.html" : "index-all.html",
            },
            new String[] { "Prev", "Next" },        // nav2
            new String[] {                          // nav3
                "Frames",    "index.html",
                "No Frames", "overview-summary.html",
            },
            new String[] {                          // nav4
                "All Types", "alldefinitions-noframe.html",
            },
            new Runnable[] {                        // renderHeaders
                () -> {
                    OverviewSummaryHtml.this.l(
"      <h1>" + (options.docTitle != null ? options.docTitle : "ANT Library Overview") + "</h1>"
                    );
                },
                overviewFirstSentenceHtml.isEmpty() ? null : () -> {
                    this.l(
"      <div class=\"docSummary\">",
"        <div class=\"subTitle\">",
"          <div class=\"block\">" + overviewFirstSentenceHtml + "</div>",
"        </div>"
                    );
                    if (!overviewHtml.isEmpty()) {
                        this.l(
"        <p>See: <a href=\"#description\">Description</a></p>"
                        );
                    }
                    this.l(
"      </div>"
                    );
                }
            },
            overviewHtml.isEmpty() ? null : () -> { // epilog
                this.l(
"      <a name=\"description\" />",
"      " + overviewHtml
                );

            },
            sections                                // sections
        );
    }
}
