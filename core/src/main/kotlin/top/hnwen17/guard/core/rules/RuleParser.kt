package top.hnwen17.guard.core.rules

/**
 * 严格规则解析器（QH-P07-02）。
 *
 * 为什么手写 JSON：core 不引入 JSON 依赖（低资源+复用原则），且合同要求
 * **拒绝重复键**——通用库默认"后者覆盖"恰是我们要拒绝的行为。
 *
 * 拒绝项：畸形 JSON、重复键、嵌套超限、字符串超限、未知字段、未知动作类型、
 * 规则数超限、版本区间倒置、包大小超限。所有失败以 [RuleParser.RuleParseResult.Error]
 * 返回稳定错误码，绝不抛异常穿透，不部分成功。
 */
object RuleParser {

    sealed class RuleParseResult {
        data class Ok(val pack: RulePack, val sourceBytes: Int) : RuleParseResult()
        data class Error(val code: RuleErrorCode, val message: String, val offset: Int = -1) : RuleParseResult()
    }

    fun parse(bytes: ByteArray): RuleParseResult {
        if (bytes.size > RuleLimits.MAX_PACKAGE_BYTES) {
            return RuleParseResult.Error(RuleErrorCode.PACKAGE_TOO_LARGE, "package ${bytes.size}B > ${RuleLimits.MAX_PACKAGE_BYTES}B")
        }
        val lexer = Json(String(bytes, Charsets.UTF_8).toCharArray()) // QH-P18 根因修复：此前逐字节 Latin-1 映射，UTF-8 中文值全部乱码
        val root = try {
            lexer.parseValue()
        } catch (e: Json.Abort) {
            return RuleParseResult.Error(lexer.errorCode, lexer.errorMessage, lexer.failureOffset)
        } ?: return RuleParseResult.Error(RuleErrorCode.MALFORMED_JSON, "not a JSON value")
        return try {
            mapPack(root, bytes.size)
        } catch (e: MapError) {
            RuleParseResult.Error(e.code, e.message ?: "invalid", e.offset)
        }
    }

    private class MapError(val code: RuleErrorCode, message: String, val offset: Int = -1) : RuntimeException(message)

    // ---------- JSON 词法（strict，拒绝重复键/超限/超深） ----------

    private sealed class JsonValue {
        data class Obj(val entries: List<Pair<String, JsonValue>>) : JsonValue() {
            /** 有重复键时返回 null（合同要求拒绝，而非后者覆盖）。 */
            fun unique(): Map<String, JsonValue>? =
                entries.groupBy { it.first }.let { groups -> if (groups.any { it.value.size > 1 }) null else entries.toMap() }
        }
        data class Arr(val items: List<JsonValue>) : JsonValue()
        data class Str(val value: String) : JsonValue()
        data class Num(val value: Long) : JsonValue()
        data class Bool(val value: Boolean) : JsonValue()
        object Null : JsonValue()
    }

    private class Json(val chars: CharArray) {
        class Abort : RuntimeException()

        var pos = 0
        var errorCode = RuleErrorCode.MALFORMED_JSON
        var errorMessage = ""
        var failureOffset = -1

        private fun fail(code: RuleErrorCode, message: String): Nothing {
            errorCode = code; errorMessage = message; failureOffset = pos
            throw Abort()
        }

        fun parseValue(): JsonValue? {
            skipWs()
            val v = value(0)
            skipWs()
            if (pos != chars.size) fail(RuleErrorCode.MALFORMED_JSON, "trailing content at $pos")
            return v
        }

        private fun value(depthIn: Int): JsonValue {
            if (depthIn > RuleLimits.MAX_NESTING_DEPTH) fail(RuleErrorCode.NESTING_TOO_DEEP, "depth > ${RuleLimits.MAX_NESTING_DEPTH}")
            return when (peek()) {
                '{' -> obj(depthIn)
                '[' -> arr(depthIn)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun obj(depthIn: Int): JsonValue {
            expect('{')
            val entries = mutableListOf<Pair<String, JsonValue>>()
            skipWs()
            if (peek() == '}') { pos++; return JsonValue.Obj(entries) }
            while (true) {
                skipWs()
                val key = string()
                skipWs(); expect(':'); skipWs()
                entries.add(key to value(depthIn + 1))
                skipWs()
                when (peek()) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return JsonValue.Obj(entries) }
                    else -> fail(RuleErrorCode.MALFORMED_JSON, "expected , or } at $pos")
                }
            }
        }

        private fun arr(depthIn: Int): JsonValue {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWs()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                skipWs()
                items.add(value(depthIn + 1))
                skipWs()
                when (peek()) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> fail(RuleErrorCode.MALFORMED_JSON, "expected , or ] at $pos")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= chars.size) fail(RuleErrorCode.MALFORMED_JSON, "unterminated string")
                val c = chars[pos]
                when {
                    c == '"' -> {
                        pos++
                        val s = sb.toString()
                        if (s.toByteArray(Charsets.UTF_8).size > RuleLimits.MAX_STRING_BYTES) {
                            fail(RuleErrorCode.STRING_TOO_LONG, "string ${s.length} chars > ${RuleLimits.MAX_STRING_BYTES}B")
                        }
                        return s
                    }
                    c == '\\' -> {
                        pos++
                        if (pos >= chars.size) fail(RuleErrorCode.MALFORMED_JSON, "unterminated escape")
                        when (val esc = chars[pos]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= chars.size) fail(RuleErrorCode.MALFORMED_JSON, "bad unicode escape")
                                val hex = String(chars, pos + 1, 4)
                                val code = hex.toIntOrNull(16) ?: fail(RuleErrorCode.MALFORMED_JSON, "bad unicode escape $hex")
                                sb.append(code.toChar()); pos += 4
                            }
                            else -> fail(RuleErrorCode.MALFORMED_JSON, "bad escape \\$esc at $pos")
                        }
                        pos++
                    }
                    c.code < 0x20 -> fail(RuleErrorCode.MALFORMED_JSON, "control char in string at $pos")
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun number(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            while (pos < chars.size && chars[pos].isDigit()) pos++
            if (pos == start) fail(RuleErrorCode.MALFORMED_JSON, "expected number at $pos")
            // 拒绝小数/指数：规则字段均为整数（version/versionCode/ms），避免浮点歧义
            if (pos < chars.size && (chars[pos] == '.' || chars[pos] == 'e')) {
                fail(RuleErrorCode.MALFORMED_JSON, "non-integer number at $start")
            }
            val text = String(chars, start, pos - start)
            val n = text.toLongOrNull() ?: fail(RuleErrorCode.MALFORMED_JSON, "number out of range: $text")
            return JsonValue.Num(n)
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (pos + word.length > chars.size ||
                String(chars, pos, word.length) != word) {
                fail(RuleErrorCode.MALFORMED_JSON, "bad literal at $pos")
            }
            pos += word.length
            return v
        }

        private fun skipWs() {
            // 标准 JSON 空白：空格/制表/换行/回车（其他控制字符仍拒绝）
            while (pos < chars.size && chars[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (pos >= chars.size || chars[pos] != c) fail(RuleErrorCode.MALFORMED_JSON, "expected '$c' at $pos")
            pos++
        }

        private fun peek(): Char {
            if (pos >= chars.size) fail(RuleErrorCode.MALFORMED_JSON, "unexpected end")
            return chars[pos]
        }
    }

    // ---------- Schema 映射（严格：未知字段/未知动作拒绝，错误码具体） ----------

    private fun str(obj: Map<String, JsonValue>, key: String): String =
        (obj[key] as? JsonValue.Str)?.value ?: throw MapError(RuleErrorCode.MISSING_REQUIRED_FIELD, key)

    private fun optStr(obj: Map<String, JsonValue>, key: String): String? =
        (obj[key] as? JsonValue.Str)?.value

    private fun num(obj: Map<String, JsonValue>, key: String): Long =
        (obj[key] as? JsonValue.Num)?.value ?: throw MapError(RuleErrorCode.MISSING_REQUIRED_FIELD, key)

    private fun optNum(obj: Map<String, JsonValue>, key: String): Long? =
        (obj[key] as? JsonValue.Num)?.value

    private fun sub(obj: Map<String, JsonValue>, key: String): Map<String, JsonValue> =
        (obj[key] as? JsonValue.Obj)?.let { it.unique() } ?: throw MapError(RuleErrorCode.DUPLICATE_KEY, key)

    private fun checkUnknownKeys(obj: Map<String, JsonValue>, allowed: Set<String>, where: String) {
        val unknown = obj.keys - allowed
        if (unknown.isNotEmpty()) throw MapError(RuleErrorCode.MALFORMED_JSON, "$where unknown fields $unknown")
    }

    private fun mapPack(root: JsonValue, sourceBytes: Int): RuleParseResult {
        val obj = (root as? JsonValue.Obj)?.unique()
            ?: return RuleParseResult.Error(RuleErrorCode.DUPLICATE_KEY, "duplicate key in pack root")
        checkUnknownKeys(obj, setOf("schemaVersion", "id", "version", "provenance", "rules"), "pack")
        val version = num(obj, "schemaVersion").toInt()
        if (version != RuleLimits.SCHEMA_VERSION) {
            return RuleParseResult.Error(RuleErrorCode.UNKNOWN_SCHEMA_VERSION, "schemaVersion=$version")
        }
        val id = str(obj, "id")
        val packVersion = num(obj, "version").toInt()
        val provenance = mapProvenance(sub(obj, "provenance"))
        val rulesArr = (obj["rules"] as? JsonValue.Arr)?.items
            ?: throw MapError(RuleErrorCode.MISSING_REQUIRED_FIELD, "rules")
        if (rulesArr.size > RuleLimits.MAX_RULES) {
            throw MapError(RuleErrorCode.TOO_MANY_RULES, "rules=${rulesArr.size}")
        }
        if (rulesArr.isEmpty()) throw MapError(RuleErrorCode.EMPTY_RULE_SET, "no rules")
        val seen = HashSet<String>()
        val rules = mutableListOf<UiRule>()
        for ((index, item) in rulesArr.withIndex()) {
            val ruleObj = (item as? JsonValue.Obj)?.unique()
                ?: throw MapError(RuleErrorCode.DUPLICATE_KEY, "rules[$index]")
            val rule = mapRule(ruleObj, index)
            if (!seen.add(rule.id)) {
                throw MapError(RuleErrorCode.DUPLICATE_RULE_ID, "duplicate id=${rule.id}")
            }
            rules.add(rule)
        }
        return RuleParseResult.Ok(RulePack(version, id, packVersion, provenance, rules), sourceBytes)
    }

    private fun mapProvenance(obj: Map<String, JsonValue>): RulePack.Provenance {
        checkUnknownKeys(obj, setOf("author", "license", "source"), "provenance")
        return RulePack.Provenance(str(obj, "author"), str(obj, "license"), str(obj, "source"))
    }

    private fun mapRule(obj: Map<String, JsonValue>, index: Int): UiRule {
        checkUnknownKeys(obj, setOf("id", "version", "provenance", "target", "page", "match", "action", "postcondition", "expiresAtEpochMs"), "rules[$index]")
        val id = str(obj, "id")
        val version = num(obj, "version").toInt()
        val provenance = mapProvenance(sub(obj, "provenance"))
        val target = mapTarget(sub(obj, "target"))
        val page = (obj["page"] as? JsonValue.Obj)?.let { it.unique() }?.let { mapPage(it) }
        val match = mapMatch(sub(obj, "match"))
        val action = mapAction(sub(obj, "action"))
        val post = (obj["postcondition"] as? JsonValue.Obj)?.let { it.unique() }?.let { mapPost(it) }
        val expires = optNum(obj, "expiresAtEpochMs")
        return UiRule(id, version, provenance, target, page, match, action, post, expires)
    }

    private fun mapTarget(obj: Map<String, JsonValue>): UiRule.Target {
        checkUnknownKeys(obj, setOf("package", "minVersionCode", "maxVersionCode"), "target")
        val minV = num(obj, "minVersionCode")
        val maxV = num(obj, "maxVersionCode")
        if (minV > maxV) throw MapError(RuleErrorCode.INVALID_VERSION_RANGE, "$minV>$maxV")
        return UiRule.Target(str(obj, "package"), minV, maxV) // package="*" 为通用规则通配（RuleIndex.WILDCARD_PACKAGE）
    }

    private fun mapPage(obj: Map<String, JsonValue>): UiRule.PageConstraint {
        checkUnknownKeys(obj, setOf("requiredViewIds", "mustNotHave"), "page")
        val required = stringListOf(obj, "requiredViewIds")
        val forbidden = stringListOf(obj, "mustNotHave")
        if (required.size > RuleLimits.MAX_VIEW_IDS_PER_PAGE || forbidden.size > RuleLimits.MAX_VIEW_IDS_PER_PAGE) {
            throw MapError(RuleErrorCode.STRING_TOO_LONG, "page view id list too long")
        }
        return UiRule.PageConstraint(required, forbidden)
    }

    private fun mapMatch(obj: Map<String, JsonValue>): UiRule.MatchCondition {
        checkUnknownKeys(obj, setOf("viewId", "viewIdContains", "className", "classNameSuffix", "textEquals", "textContains", "descContains", "clickable", "parentViewId", "maxWidth", "maxHeight", "textEmpty", "windowTextContainsAny", "windowTextEqualsAny", "windowViewIdContainsAny", "ancestorViewIdContainsAny", "childCountMax", "childCountEquals", "lastChild", "siblingTextContainsAny", "activityIds", "excludeActivityIds", "maxDepth"), "match")
        val viewId = optStr(obj, "viewId")
        val viewIdContains = optStr(obj, "viewIdContains")
        val className = optStr(obj, "className")
        val classNameSuffix = optStr(obj, "classNameSuffix")
        val textEquals = optStr(obj, "textEquals")
        val textContains = optStr(obj, "textContains")
        val descContains = optStr(obj, "descContains")
        val clickable = (obj["clickable"] as? JsonValue.Bool)?.value
        val parentViewId = optStr(obj, "parentViewId")
        val maxWidth = (optNum(obj, "maxWidth") ?: -1L).toInt().takeIf { it > 0 }
        val maxHeight = (optNum(obj, "maxHeight") ?: -1L).toInt().takeIf { it > 0 }
        val textEmpty = (obj["textEmpty"] as? JsonValue.Bool)?.value
        val windowTextContainsAny = optStrList(obj, "windowTextContainsAny")
        val windowTextEqualsAny = optStrList(obj, "windowTextEqualsAny")
        val windowViewIdContainsAny = optStrList(obj, "windowViewIdContainsAny")
        val ancestorViewIdContainsAny = optStrList(obj, "ancestorViewIdContainsAny")
        val childCountMax = (optNum(obj, "childCountMax") ?: -1L).toInt().takeIf { it >= 0 }
        val childCountEquals = (optNum(obj, "childCountEquals") ?: -1L).toInt().takeIf { it >= 0 }
        val lastChild = (obj["lastChild"] as? JsonValue.Bool)?.value
        val siblingTextContainsAny = optStrList(obj, "siblingTextContainsAny")
        // QH-阶段1：Activity 白/黑名单（全类名精确匹配；解析层校验格式与限额）
        val activityIds = optStrList(obj, "activityIds")
        val excludeActivityIds = optStrList(obj, "excludeActivityIds")
        for (list in listOf(activityIds, excludeActivityIds)) {
            if (list == null) continue
            if (list.size > 8) throw MapError(RuleErrorCode.STRING_TOO_LONG, "activity list > 8")
            for (a in list) {
                if (a.length < 3 || a.length > 256) throw MapError(RuleErrorCode.STRING_TOO_LONG, "bad activity name length")
                if (!Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(a)) throw MapError(RuleErrorCode.STRING_TOO_LONG, "bad activity name")
            }
        }
        val maxDepth = (optNum(obj, "maxDepth") ?: RuleLimits.DEFAULT_MAX_DEPTH.toLong()).toInt()
        if (viewId == null && viewIdContains == null && className == null && classNameSuffix == null && textEquals == null && textContains == null && descContains == null && clickable == null && maxWidth == null && maxHeight == null && textEmpty == null && windowTextContainsAny == null && windowTextEqualsAny == null && windowViewIdContainsAny == null && ancestorViewIdContainsAny == null && childCountMax == null && childCountEquals == null && lastChild == null && siblingTextContainsAny == null && activityIds == null && excludeActivityIds == null) {
            throw MapError(RuleErrorCode.MISSING_REQUIRED_FIELD, "match has no condition")
        }
        if (maxDepth < 1 || maxDepth > RuleLimits.MAX_NESTING_DEPTH) {
            throw MapError(RuleErrorCode.NESTING_TOO_DEEP, "maxDepth=$maxDepth")
        }
        return UiRule.MatchCondition(viewId, viewIdContains, className, classNameSuffix, textEquals, textContains, descContains, clickable, parentViewId, maxWidth, maxHeight, textEmpty, windowTextContainsAny, windowTextEqualsAny, windowViewIdContainsAny, ancestorViewIdContainsAny, childCountMax, childCountEquals, lastChild, siblingTextContainsAny, activityIds, excludeActivityIds, maxDepth)
    }

    private fun mapAction(obj: Map<String, JsonValue>): UiRule.RuleAction {
        checkUnknownKeys(obj, setOf("type", "maxAttempts", "cooldownMs"), "action")
        val typeName = str(obj, "type")
        val known = try {
            UiRule.RuleAction.ActionType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            throw MapError(RuleErrorCode.UNKNOWN_ACTION_TYPE, typeName) // 未知动作/任意命令形态一律拒绝
        }
        val attempts = (optNum(obj, "maxAttempts") ?: 1L).toInt()
        val cooldown = optNum(obj, "cooldownMs") ?: 0L
        if (attempts < 1 || attempts > RuleLimits.MAX_ATTEMPTS) {
            throw MapError(RuleErrorCode.MALFORMED_JSON, "maxAttempts=$attempts")
        }
        if (cooldown < 0 || cooldown > RuleLimits.MAX_COOLDOWN_MS) {
            throw MapError(RuleErrorCode.MALFORMED_JSON, "cooldownMs=$cooldown")
        }
        return UiRule.RuleAction(known, attempts, cooldown)
    }

    private fun mapPost(obj: Map<String, JsonValue>): UiRule.Postcondition {
        checkUnknownKeys(obj, setOf("absentViewId", "timeoutMs"), "postcondition")
        val timeout = num(obj, "timeoutMs")
        if (timeout < 0 || timeout > 10_000L) throw MapError(RuleErrorCode.MALFORMED_JSON, "timeoutMs=$timeout")
        return UiRule.Postcondition(str(obj, "absentViewId"), timeout)
    }

    private fun optStrList(obj: Map<String, JsonValue>, key: String): List<String>? =
        if (obj.containsKey(key)) stringListOf(obj, key).takeIf { it.isNotEmpty() } else null

    private fun stringListOf(obj: Map<String, JsonValue>, key: String): List<String> =
        ((obj[key] as? JsonValue.Arr)?.items?.mapNotNull { it as? JsonValue.Str })?.map { it.value } ?: emptyList()
}
