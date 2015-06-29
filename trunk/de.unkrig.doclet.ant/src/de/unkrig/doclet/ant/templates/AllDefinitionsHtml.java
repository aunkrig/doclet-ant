
package de.unkrig.doclet.ant.templates;

import java.util.List;

import com.sun.javadoc.RootDoc;

import de.unkrig.commons.doclet.html.Html;
import de.unkrig.commons.lang.protocol.Longjump;
import de.unkrig.doclet.ant.AntDoclet.AntType;
import de.unkrig.doclet.ant.AntDoclet.AntTypeGroup;
import de.unkrig.notemplate.javadocish.Options;
import de.unkrig.notemplate.javadocish.templates.AbstractPackageFrameHtml;

public class AllDefinitionsHtml extends AbstractPackageFrameHtml {

    public void
    render(final List<AntTypeGroup> antTypeGroups, final RootDoc rootDoc, Options options, final Html html) {
        super.rPackageFrameHtml(
            "All types",
            "overview-summary.html",
            options,
            "stylesheet.css",
            new Runnable() {

                @Override public void
                run() {
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
    rootDoc,
    antType.classDoc,
    false,             // plain
    null,              // label
    "definitionFrame", // target
    rootDoc
) + "</code></dd>"
                                );
                            } catch (Longjump l) {}
                        }
                    }
                    AllDefinitionsHtml.this.l(
    "    </dl>"
                    );
                }
            }
        );
    }
}
