package io.web.testing

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8

object JsonTestSupport {

    /** Strings that broke (or would break) hand-built JSON: quotes, backslashes, control chars, unicode. */
    val nastyStrings: List[String] = List(
        "plain",
        "",
        "quote \" inside",
        "\"",
        "back\\slash and \\\\ double",
        "trailing backslash \\",
        "line\nbreak",
        "crlf\r\nend",
        "tab\tchar",
        "controls \u0000 \u0001 \u0008 \u000b \u000c \u001f and DEL \u007f",
        "line separator   paragraph separator  ",
        "emoji 😀 🎉 👩‍🔬",
        "ñandú café 中文 العربية Ελληνικά",
        "</script><script>alert(1)</script>",
        "\"},{\"injected\":true,\"x\":\"",
        "{\"json\":\"inside\"}"
    )

    /**
     * Parses `json` the way a client does — from its UTF-8 bytes — with spray-json AND an
     * independent parser (play-json / Jackson), and checks both read the same values.
     */
    def parseAsClient(json: String): JsValue = {
        val bytes  = json.getBytes(UTF_8)
        val spray  = JsonParser(ParserInput(bytes))
        val other  = play.api.libs.json.Json.parse(bytes)
        val again  = JsonParser(play.api.libs.json.Json.stringify(other))
        if (again != spray) throw new AssertionError(s"spray-json and play-json disagree on: $json")
        spray
    }

    def field(value: JsValue, name: String): JsValue = value.asJsObject.fields.getOrElse(name,
        throw new AssertionError(s"missing member '$name' in ${value.compactPrint}"))

    def str(value: JsValue, name: String): String = field(value, name) match {
        case JsString(s) => s
        case other       => throw new AssertionError(s"'$name' is not a string: $other")
    }

    def int(value: JsValue, name: String): Int = field(value, name) match {
        case JsNumber(n) if n.isValidInt => n.toInt
        case other                       => throw new AssertionError(s"'$name' is not an integer: $other")
    }

    def array(value: JsValue, name: String): Vector[JsValue] = field(value, name) match {
        case JsArray(items) => items
        case other          => throw new AssertionError(s"'$name' is not an array: $other")
    }

    def memberNames(value: JsValue): List[String] = value.asJsObject.fields.keys.toList
}
