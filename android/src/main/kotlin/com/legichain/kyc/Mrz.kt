package com.legichain.kyc

/** ICAO check digits must pass before a camera capture can start chip access. */
internal data class Mrz(val documentNumber: String, val birth: String, val expiry: String, val text: String) {
    companion object {
        fun check(value: String, digit: Char): Boolean {
            if (!digit.isDigit()) return false
            val weights = intArrayOf(7, 3, 1)
            return value.mapIndexed { i, c ->
                val n = when(c) { '<' -> 0; in '0'..'9' -> c-'0'; in 'A'..'Z' -> c-'A'+10; else -> return false }
                n * weights[i % 3]
            }.sum() % 10 == digit-'0'
        }
        fun parse(text: String): Mrz? {
            val lines = text.uppercase().lines().map { it.replace(" ", "").trim() }
                .filter { it.matches(Regex("[A-Z0-9<]{30}|[A-Z0-9<]{36}|[A-Z0-9<]{44}")) }
            for (i in lines.indices) {
                val a=lines[i]; val b=lines.getOrNull(i+1) ?: continue
                if (a.length == 44 && b.length == 44 && a.startsWith("P")) {
                    if (check(b.substring(0,9),b[9]) && check(b.substring(13,19),b[19]) &&
                        check(b.substring(21,27),b[27]) && check(b.substring(0,10)+b.substring(13,20)+b.substring(21,43),b[43]))
                        return Mrz(b.substring(0,9).trimEnd('<'), b.substring(13,19), b.substring(21,27), "$a\n$b")
                }
                if (a.length==30 && b.length==30 && lines.getOrNull(i+2)?.length==30) {
                    if(check(a.substring(5,14),a[14]) && check(b.substring(0,6),b[6]) &&
                       check(b.substring(8,14),b[14]) && check(a.substring(5,30)+b.substring(0,7)+b.substring(8,15)+b.substring(18,29),b[29]))
                        return Mrz(a.substring(5,14).trimEnd('<'),b.substring(0,6),b.substring(8,14),"$a\n$b\n${lines[i+2]}")
                }
                if(a.length==36 && b.length==36) {
                    if(check(b.substring(0,9),b[9]) && check(b.substring(13,19),b[19]) &&
                       check(b.substring(21,27),b[27]) && check(b.substring(0,10)+b.substring(13,20)+b.substring(21,35),b[35]))
                        return Mrz(b.substring(0,9).trimEnd('<'),b.substring(13,19),b.substring(21,27),"$a\n$b")
                }
            }
            return null
        }
    }
}
