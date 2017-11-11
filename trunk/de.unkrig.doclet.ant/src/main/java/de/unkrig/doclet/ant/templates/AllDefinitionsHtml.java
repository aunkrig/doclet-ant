
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

import java.util.Collection;

import com.sun.javadoc.RootDoc;

import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.commons.nullanalysis.Nullable;
import de.unkrig.doclet.ant.AntDoclet.AntTypeGroup;
import de.unkrig.notemplate.commons.lang.ConsumerUtil8;
import de.unkrig.notemplate.javadocish.Options;
import de.unkrig.notemplate.javadocish.templates.AbstractBottomLeftFrameHtml;

/**
 * Renders the contents of the "All Definitions" left frame.
 */
public
class AllDefinitionsHtml extends AbstractBottomLeftFrameHtml {

    /**
     * Renders the contents of the "All Definitions" frame.
     */
    public void
    render(
        final Collection<AntTypeGroup> antTypeGroups,
        final RootDoc                  rootDoc,
        Options                        options,
        final Html                     html,
        @Nullable final String         target
    ) {

        super.rBottomLeftFrameHtml(
            "All types",                       // windowTitle
            options,                           // options
            new String[] { "stylesheet.css" }, // stylesheetLinks
            "All types",                       // heading
            "overview-summary.html",           // headingLink
            null,                              // renderIndexHeader
            () -> {                            // renderIndexContainer
                antTypeGroups.stream().filter(typeGroup -> !typeGroup.types.isEmpty()).forEach(typeGroup -> {

                    AllDefinitionsHtml.this.l(
"    <h2 title=\"" + typeGroup.heading + "\">" + typeGroup.heading + "</h2>",
"    <ul title=\"" + typeGroup.heading + "\">"
                    );

                    typeGroup.types.stream().forEach(ConsumerUtil8.asJavaUtil(antType -> {
                        Longjump.catchLongjump(() -> {
                            AllDefinitionsHtml.this.l(
"      <li>" + html.makeLink(rootDoc, antType.classDoc, false, null, target, rootDoc) + "</li>"
                            );
                        });
                    }));

                    AllDefinitionsHtml.this.l(
"    </ul>"
                    );
                });
            }
        );
    }
}
