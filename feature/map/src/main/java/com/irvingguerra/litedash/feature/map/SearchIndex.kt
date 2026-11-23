package com.irvingguerra.litedash.feature.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.Normalizer
import kotlin.math.min

/**
 * La búsqueda sin internet: el índice SRCH1 de un estado (lo arma armar_indice.py), leído por partes con mmap; nunca
 * se carga entero. Cada palabra de lo que escribes encuentra las del diccionario que empiezan igual y se quedan los
 * registros que las tienen todas. Solo lee con índices absolutos: se puede llamar desde varios hilos.
 */
class SearchIndex private constructor(
    private val channel: FileChannel,
    private val buf: MappedByteBuffer,
    private val h: Header,
) : AutoCloseable {

    override fun close() = channel.close()

    /** Hasta [limit] resultados para [query] en el orden de [rankTier] (ver SearchRank.kt). */
    fun search(query: String, nearLat: Double?, nearLon: Double?, limit: Int = 20): List<SearchHit> {
        val words = normalizeForSearch(query)
        val terms = words.distinct().map { it.toByteArray(Charsets.UTF_8) }
        if (terms.isEmpty() || limit <= 0) return emptyList()
        var matches = terms.map { collect(it, fuzzy = false) }
        var found = intersect(matches)
        if (found.isEmpty()) {
            // Segunda pasada: un error de dedo en las palabras largas.
            matches = terms.map { collect(it, fuzzy = codePoints(it) >= FUZZY_MIN_LETTERS) }
            found = intersect(matches)
        }
        if (found.isEmpty()) return emptyList()
        val near = nearLat != null && nearLon != null
        // Una llave por candidato (sin objetos): el escalón (ver [rankTier]), la clase, la distancia (o, sin saber dónde
        // estás, la importancia del tipo) y el registro. Primero con el escalón barato (¿todas las palabras completas?) y
        // los lugares destacados delante, para que sobrevivan al recorte; luego el escalón de verdad solo en los primeros
        // [TIER_CANDIDATES] (leer y normalizar el nombre de miles sería caro).
        fun key(r: Int, tier: Int, cls: Int): Long {
            val order = if (near) distance(r, nearLat!!, nearLon!!).toFloat() else rank(kind(r)).toFloat()
            return (tier.toLong() shl TIER_SHIFT) or (cls.toLong() shl CLASS_SHIFT) or
                ((java.lang.Float.floatToRawIntBits(order) ushr 1).toLong() shl 28) or r.toLong()
        }
        var keys = LongArray(found.size) { i ->
            val r = found[i]
            key(r, if (matches.all { it.exact.binarySearch(r) >= 0 }) TIER_CONTAINS else TIER_OTHER, if (prominent(r)) 0 else 1)
        }
        keys.sort()
        if (keys.size > TIER_CANDIDATES) keys = keys.copyOf(TIER_CANDIDATES)
        val tiers = IntArray(keys.size)
        // El más cercano de los que empiezan con lo escrito: decide si un nombre exacto corriente se queda delante.
        var bestStartsKm = Double.MAX_VALUE
        for (i in keys.indices) {
            val r = (keys[i] and INDEX_MASK).toInt()
            tiers[i] = tier(normalizeForSearch(name(u24(record(r) + 8)).orEmpty()), words)
            if (near && tiers[i] == TIER_STARTS) bestStartsKm = minOf(bestStartsKm, distance(r, nearLat!!, nearLon!!))
        }
        for (i in keys.indices) {
            val r = (keys[i] and INDEX_MASK).toInt()
            val p = prominent(r)
            val km = if (near) distance(r, nearLat!!, nearLon!!) else null
            val t = rankTier(tiers[i], isAnchor(PlaceKind.of(kind(r)), PlaceCategory.of(buf.get(record(r) + 1).toInt() and 0xFF)), km, bestStartsKm.takeIf { it < Double.MAX_VALUE })
            keys[i] = key(r, t, rankClass(t, p))
        }
        keys.sort()
        val hits = ArrayList<SearchHit>(min(limit, keys.size))
        val seen = LongArray(limit)
        for (k in keys) {
            if (hits.size == limit) break
            val r = (k and INDEX_MASK).toInt()
            // Mismo nombre en la misma localidad: se queda el primero (el más cercano de su tipo).
            val nameId = u24(record(r) + 8)
            val localityId = u24(record(r) + 14)
            val same = (nameId.toLong() shl 24) or localityId.toLong()
            if ((0 until hits.size).any { seen[it] == same }) continue
            seen[hits.size] = same
            val order = java.lang.Float.intBitsToFloat(((k ushr 28) and 0x3FFFFFFF).toInt() shl 1) +
                (k ushr TIER_SHIFT) * TIER_STEP + ((k ushr CLASS_SHIFT) and 3) * CLASS_STEP
            hits += hit(r, nameId, localityId, if (near) distance(r, nearLat!!, nearLon!!) else null, order)
        }
        return hits
    }

    /** Lo que una palabra encontró: todos sus registros y los de la palabra idéntica (ordenados). */
    private class TermMatch(val all: IntArray, val exact: IntArray)

    /**
     * Los registros de las palabras que empiezan con [term] (y, si [fuzzy], las que están a un cambio de él entre las
     * que comparten sus 2 primeras letras), con los topes de palabras y registros.
     */
    private fun collect(term: ByteArray, fuzzy: Boolean): TermMatch {
        val scan = if (fuzzy) firstLetters(term, 2) else term
        val termText = if (fuzzy) String(term, Charsets.UTF_8) else ""
        var word = ByteArray(64)
        var wordLen = 0
        val r = Cursor(blockOffset(firstBlock(scan)))
        var all = IntArray(64)
        var count = 0
        var exact = IntArray(0)
        var words = 0
        while (r.p < h.table) {
            // Una palabra con "front coding": cuánto comparte con la anterior y el resto.
            val common = r.varint()
            val rest = r.varint()
            if (common > wordLen || r.p + rest > h.table) break
            if (common + rest > word.size) word = word.copyOf(common + rest + 32)
            for (i in 0 until rest) word[common + i] = buf.get(r.p + i)
            r.p += rest
            wordLen = common + rest
            val postings = r.varint()
            val cmp = comparePrefix(word, wordLen, scan)
            if (cmp > 0) break
            val take = cmp == 0 && (startsWith(word, wordLen, term) ||
                (fuzzy && withinOneEdit(termText, String(word, 0, wordLen, Charsets.UTF_8))))
            val isExact = take && wordLen == term.size && startsWith(word, wordLen, term)
            // Un índice dañado no aparta memoria de más ni lee fuera de los registros.
            if (postings < 0 || postings > h.entries) break
            if (isExact) exact = IntArray(postings)
            var id = 0
            for (i in 0 until postings) {
                id += r.varint()
                if (!take || id < 0 || id >= h.entries) continue
                if (isExact) exact[i] = id
                if (count < MAX_RECORDS_PER_TERM) {
                    if (count == all.size) all = all.copyOf(all.size * 2)
                    all[count++] = id
                }
            }
            if (take && ++words >= MAX_WORDS_PER_TERM) break
        }
        val sorted = all.copyOf(count)
        if (words > 1) sorted.sort()
        return TermMatch(dedupeSorted(sorted), exact)
    }

    /** El último bloque cuya primera palabra no pasa de [key] (los bloques van en orden de bytes UTF-8). */
    private fun firstBlock(key: ByteArray): Int {
        var lo = 0
        var hi = h.blocks - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (compareFirstWord(mid, key) <= 0) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Compara la primera palabra (completa) del bloque [b] con [key], byte por byte sin signo. */
    private fun compareFirstWord(b: Int, key: ByteArray): Int {
        val r = Cursor(blockOffset(b))
        r.varint() // prefijo común: 0
        val len = r.varint()
        for (i in 0 until min(len, key.size)) {
            val d = (buf.get(r.p + i).toInt() and 0xFF) - (key[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return len - key.size
    }

    /** ¿El registro [r] es un lugar destacado ([isProminent])? */
    private fun prominent(r: Int): Boolean =
        isProminent(PlaceKind.of(kind(r)), PlaceCategory.of(buf.get(record(r) + 1).toInt() and 0xFF))

    private fun hit(r: Int, nameId: Int, localityId: Int, km: Double?, order: Double): SearchHit {
        val base = record(r)
        return SearchHit(
            name = name(nameId) ?: "",
            alt = name(u24(base + 11)),
            locality = name(localityId),
            address = null,
            kind = PlaceKind.of(kind(r)),
            category = PlaceCategory.of(buf.get(base + 1).toInt() and 0xFF),
            lat = lat(r),
            lon = lon(r),
            distanceKm = km,
            online = false,
            order = order,
        )
    }

    private fun name(id: Int): String? {
        if (id == NONE || id >= h.names) return null
        val start = buf.getInt(h.nameOffsets + id * 4)
        val end = buf.getInt(h.nameOffsets + id * 4 + 4)
        if (start < 0 || end < start || h.nameBlob + end > h.dictionary) return null
        val bytes = ByteArray(end - start) { buf.get(h.nameBlob + start + it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun record(r: Int) = h.records + r * RECORD
    private fun kind(r: Int) = buf.get(record(r)).toInt() and 0xFF
    private fun lat(r: Int) = h.minLat + u24(record(r) + 2).toDouble() / NONE * (h.maxLat - h.minLat)
    private fun lon(r: Int) = h.minLon + u24(record(r) + 5).toDouble() / NONE * (h.maxLon - h.minLon)
    private fun distance(r: Int, lat: Double, lon: Double) = distanceKm(lat, lon, lat(r), lon(r))

    private fun u24(p: Int) =
        (buf.get(p).toInt() and 0xFF) or ((buf.get(p + 1).toInt() and 0xFF) shl 8) or ((buf.get(p + 2).toInt() and 0xFF) shl 16)

    private fun blockOffset(b: Int) = h.dictionary + buf.getInt(h.table + b * 4)

    /** Un lector de varints con su propia posición (uno por búsqueda: nada compartido entre hilos). */
    private inner class Cursor(var p: Int) {
        fun varint(): Int {
            var v = 0
            var shift = 0
            while (p < h.table && shift < 35) {
                val b = buf.get(p++).toInt()
                v = v or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return v
        }
    }

    internal class Header(
        val entries: Int, val names: Int, val blocks: Int,
        val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
        val records: Int, val nameOffsets: Int, val nameBlob: Int, val dictionary: Int, val table: Int,
    )

    companion object {
        private const val HEADER = 96
        private const val RECORD = 17
        private const val NONE = 0xFFFFFF
        private const val INDEX_MASK = (1L shl 28) - 1
        private const val MAX_WORDS_PER_TERM = 400
        private const val MAX_RECORDS_PER_TERM = 20_000
        private const val FUZZY_MIN_LETTERS = 5
        /** Cuántos candidatos (ya en orden aproximado) se revisan por su nombre completo para el escalón. */
        private const val TIER_CANDIDATES = 500
        // La llave: escalón (bits 60–62), clase (58–59), la distancia (float sin su último bit, 28–57) y el registro.
        private const val TIER_SHIFT = 60
        private const val CLASS_SHIFT = 58
        /** Para juntar estados (`SearchHit.order`): el escalón y la clase pesan más que cualquier distancia. */
        private const val TIER_STEP = 1e7
        private const val CLASS_STEP = 1e6

        /** Abre el índice SRCH1 de [file]; null si no lo es o está dañado. */
        fun open(file: File): SearchIndex? = runCatching {
            val channel = RandomAccessFile(file, "r").channel
            try {
                val size = channel.size()
                if (size < HEADER || size > Int.MAX_VALUE) error("tamaño")
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                buf.order(ByteOrder.LITTLE_ENDIAN)
                SearchIndex(channel, buf, header(buf, size) ?: error("cabecera"))
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }.getOrNull()

        /** Lee y revisa la cabecera: que todo quepa en el archivo y en orden. */
        private fun header(b: MappedByteBuffer, size: Long): Header? {
            if (b.get(0) != 'S'.code.toByte() || b.get(1) != 'R'.code.toByte() || b.get(2) != 'C'.code.toByte() ||
                b.get(3) != 'H'.code.toByte()
            ) return null
            if (b.getShort(4).toInt() != 1 || b.getInt(76) != RECORD) return null
            val entries = b.getInt(8).toLong() and 0xFFFFFFFFL
            val names = b.getInt(16).toLong() and 0xFFFFFFFFL
            val blocks = b.getInt(20).toLong() and 0xFFFFFFFFL
            val o = LongArray(5) { b.getInt(56 + it * 4).toLong() and 0xFFFFFFFFL }
            val (records, nameOffsets, nameBlob, dictionary, table) = o.toList()
            if (entries > INDEX_MASK || names > NONE || blocks == 0L) return null
            if (records < HEADER || records + entries * RECORD > nameOffsets) return null
            if (nameOffsets + (names + 1) * 4 > nameBlob || nameBlob > dictionary || dictionary > table) return null
            if (table + blocks * 4 > size) return null
            if (nameBlob + (b.getInt((nameOffsets + names * 4).toInt()).toLong() and 0xFFFFFFFFL) > dictionary) return null
            for (i in 0 until blocks.toInt()) {
                val start = b.getInt((table + i * 4).toInt()).toLong() and 0xFFFFFFFFL
                if (dictionary + start >= table) return null
            }
            return Header(
                entries.toInt(), names.toInt(), blocks.toInt(),
                b.getDouble(24), b.getDouble(32), b.getDouble(40), b.getDouble(48),
                records.toInt(), nameOffsets.toInt(), nameBlob.toInt(), dictionary.toInt(), table.toInt(),
            )
        }

        /** Sin saber dónde estás: ciudad, pueblo o villa > colonia o barrio > calle > lugar > caserío (menor = antes). */
        private fun rank(kind: Int) = when (kind) {
            1, 2, 3 -> 0
            5, 6, 7 -> 1
            0 -> 2
            8 -> 3
            else -> 4
        }

        /** -1 si [word] va antes que [prefix] (y no empieza con él), 0 si empieza con él, 1 si ya lo pasó. */
        private fun comparePrefix(word: ByteArray, len: Int, prefix: ByteArray): Int {
            for (i in prefix.indices) {
                if (i == len) return -1
                val d = (word[i].toInt() and 0xFF) - (prefix[i].toInt() and 0xFF)
                if (d != 0) return if (d < 0) -1 else 1
            }
            return 0
        }

        private fun startsWith(word: ByteArray, len: Int, prefix: ByteArray) =
            len >= prefix.size && comparePrefix(word, len, prefix) == 0

        /** Los bytes de las primeras [n] letras de un texto UTF-8. */
        private fun firstLetters(utf8: ByteArray, n: Int): ByteArray {
            var seen = 0
            for (i in utf8.indices) {
                if (utf8[i].toInt() and 0xC0 != 0x80 && seen++ == n) return utf8.copyOf(i)
            }
            return utf8
        }

        private fun codePoints(utf8: ByteArray) = utf8.count { it.toInt() and 0xC0 != 0x80 }

        /**
         * true si [word] o el principio de [word] (lo que aún escribes) está a un cambio de [term]: una letra de más,
         * de menos o distinta (Levenshtein ≤ 1 contra los principios de su largo −1, igual y +1).
         */
        internal fun withinOneEdit(term: String, word: String): Boolean {
            for (len in term.length - 1..term.length + 1) {
                if (len < 1 || len > word.length) continue
                if (levenshteinAtMostOne(term, word, len)) return true
            }
            return false
        }

        private fun levenshteinAtMostOne(a: String, b: String, bLen: Int): Boolean {
            if (kotlin.math.abs(a.length - bLen) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < bLen) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > bLen -> i++
                    a.length < bLen -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (bLen - j) <= 1
        }

        /** Los registros que están en todas las listas, empezando por la más chica. */
        private fun intersect(matches: List<TermMatch>): IntArray {
            val lists = matches.map { it.all }.sortedBy { it.size }
            var acc = lists[0]
            for (k in 1 until lists.size) {
                val other = lists[k]
                val out = IntArray(acc.size)
                var n = 0
                var j = 0
                for (x in acc) {
                    while (j < other.size && other[j] < x) j++
                    if (j < other.size && other[j] == x) out[n++] = x
                }
                acc = out.copyOf(n)
                if (n == 0) break
            }
            return acc
        }

        private fun dedupeSorted(a: IntArray): IntArray {
            if (a.size < 2) return a
            var n = 1
            for (i in 1 until a.size) if (a[i] != a[n - 1]) a[n++] = a[i]
            return if (n == a.size) a else a.copyOf(n)
        }
    }
}

// Igual que STOP y ABBR de armar_indice.py (sin "pl.", "p.za" ni "straße": tras normalizar nunca llegan así).
private val STOP_WORDS = setOf(
    "de", "la", "el", "los", "las", "del", "y", "e", "the", "of", "and", "da", "do", "das", "dos", "der", "die",
    "von", "du", "des", "le", "les", "di", "della",
)
private val ABBREVIATIONS = mapOf(
    "av" to "avenida", "avda" to "avenida", "blvd" to "boulevard", "blvr" to "boulevard", "calz" to "calzada",
    "priv" to "privada", "col" to "colonia", "gral" to "general", "fracc" to "fraccionamiento", "prol" to "prolongacion",
    "carr" to "carretera", "cda" to "cerrada", "pje" to "pasaje", "pza" to "plaza",
    "st" to "street", "ave" to "avenue", "rd" to "road", "dr" to "drive", "ln" to "lane", "hwy" to "highway",
    "pkwy" to "parkway", "ct" to "court", "pl" to "place", "sq" to "square", "blv" to "boulevard", "fwy" to "freeway",
    "n" to "north", "s" to "south", "e" to "east", "w" to "west", "mt" to "mount",
    "r" to "rua", "str" to "strasse", "bd" to "boulevard", "v" to "via",
)

/** Escrituras sin espacios entre palabras (CJK, hangul, tailandés, lao, jemer, birmano): van de dos en dos letras. */
private fun noSpaces(c: Int) =
    c in 0x3040..0x30FF || c in 0x3400..0x9FFF || c in 0xF900..0xFAFF || c in 0xAC00..0xD7AF ||
        c in 0x0E00..0x0EFF || c in 0x1780..0x17FF || c in 0x1000..0x109F

/** Como str.isalnum() de Python: letras, dígitos y los demás números (½, ², Ⅻ). */
private fun isAlnum(c: Int): Boolean {
    if (Character.isLetterOrDigit(c)) return true
    val t = Character.getType(c)
    return t == Character.LETTER_NUMBER.toInt() || t == Character.OTHER_NUMBER.toInt()
}

/**
 * Las palabras con que se busca, idéntico a normalize() de armar_indice.py: minúsculas, sin acentos (la ñ se queda),
 * ß → ss, solo letras y números, abreviaturas a su forma larga, sin palabras vacías; las escrituras sin espacios
 * (CJK, tailandés…) en pares de letras.
 */
internal fun normalizeForSearch(text: String): List<String> {
    val out = StringBuilder(text.length + 8)
    val lower = text.lowercase().replace("ß", "ss")
    var i = 0
    while (i < lower.length) {
        val c = lower.codePointAt(i)
        i += Character.charCount(c)
        when {
            c == 'ñ'.code -> out.append('ñ')
            // Marca \u0000: letra suelta de una escritura sin espacios.
            noSpaces(c) -> out.append(' ').appendCodePoint(c).append('\u0000')
            else -> {
                val base = Normalizer.normalize(String(Character.toChars(c)), Normalizer.Form.NFD).codePointAt(0)
                if (isAlnum(base)) out.appendCodePoint(base) else out.append(' ')
            }
        }
    }
    val words = ArrayList<String>()
    val run = ArrayList<String>()
    for (piece in out.split(' ')) {
        if (piece.isEmpty()) continue
        if (piece.endsWith('\u0000')) {
            run += piece.dropLast(1)
            continue
        }
        if (run.isNotEmpty()) { words += bigrams(run); run.clear() }
        words += ABBREVIATIONS[piece] ?: piece
    }
    if (run.isNotEmpty()) words += bigrams(run)
    return words.filter { it !in STOP_WORDS }
}

/** Pares de letras seguidas (o la letra sola si es una). */
private fun bigrams(chars: List<String>): List<String> =
    if (chars.size == 1) chars.toList() else (0 until chars.size - 1).map { chars[it] + chars[it + 1] }
