
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

import java.util.List;

import com.sun.javadoc.RootDoc;

import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.doclet.ant.AntDoclet.AntType;
import de.unkrig.doclet.ant.AntDoclet.AntTypeGroup;
import de.unkrig.notemplate.javadocish.Options;
import de.unkrig.notemplate.javadocish.templates.AbstractBottomLeftFrameHtml;

/**
 * Renders the contents of the "All Definitions" frame.
 */
public
class AllDefinitionsHtml extends AbstractBottomLeftFrameHtml {

    /**
     * Renders the contents of the "All Definitions" frame.
     */
    public void
    render(final List<AntTypeGroup> antTypeGroups, final RootDoc rootDoc, Options options, final Html html) {

        super.rBottomLeftFrameHtml(
            "All types",                       // heading
            "overview-summary.html",           // headingLink
            options,                           // options
            new String[] { "stylesheet.css" }, // stylesheetLinks
            () -> {                            // renderBody
                AllDefinitionsHtml.this.l(
"    <dl>",
""
                );
                for (AntTypeGroup typeGroup : antTypeGroups) {

                    if (typeGroup.types.isEmpty()) continue;

                    AllDefinitionsHtml.this.l(
"      <dt>" + typeGroup.typeGroupHeading + "</dt>"
                    );

                    for (final AntType antType : typeGroup.types) {
                        try {
                            AllDefinitionsHtml.this.l(
"      <dd><code>" + html.makeLink(
    rootDoc,           // from
    antType.classDoc,  // to
    false,             // plain
    null,              // label
    "classFrame",      // target
    rootDoc            // rootDoc
) + "</code></dd>"
                            );
                        } catch (Longjump l) {}
                    }
                }
                AllDefinitionsHtml.this.l(
"    </dl>"
                );
            }
        );
    }
}
