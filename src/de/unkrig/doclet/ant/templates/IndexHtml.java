
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

import de.unkrig.notemplate.NoTemplate;
import de.unkrig.notemplate.javadocish.Options;

public
class IndexHtml extends NoTemplate {

    public void
    render(Options options) {
        this.l(
"<!DOCTYPE html>",
"<html lang=\"en\">",
"  <head>",
"    <!-- Generated by " + options.generator + " -->",
"    <title>" + options.windowTitle + "</title>",
"    <script type=\"text/javascript\">",
"      var targetPage = \"\" + window.location.search;",
"      if (targetPage != \"\" && targetPage != \"undefined\") {",
"        targetPage = targetPage.substring(1);",
"      }",
"      function loadFrames() {",
"        if (targetPage != \"\" && targetPage != \"undefined\") {",
"          top.antlibFrame.location = top.targetPage;",
"        }",
"      }",
"    </script>",
"  </head>",
"  <frameset cols=\"20%,80%\" title=\"Documentation frame\" onload=\"top.loadFrames()\">",
"    <frame name=\"antlibFrame\"     src=\"alldefinitions-frame.html\" title=\"All definitions of the antlib\" />",
"    <frame name=\"definitionFrame\" src=\"overview-summary.html\"     title=\"Antlib, type, macro, preset and script definitions\" scrolling=\"yes\" />",
"    <noframes>",
"      <noscript>",
"        <div>JavaScript is disabled on your browser.</div>",
"      </noscript>",
"      <h2>Frame Alert</h2>",
"      <p>",
"        This document is designed to be viewed using the frames feature. If you see this message, you are using a",
"        non-frame-capable web client. Link to <a href=\"overview-summary.html\">Non-frame version</a>.",
"      </p>",
"    </noframes>",
"  </frameset>",
"</html>"
        );
    }
}
