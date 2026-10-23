package com.scanner.app

import java.text.Normalizer
import kotlin.math.abs

/** Minuscolo e senza accenti, con la stessa lunghezza del testo: gli indici valgono anche sul testo originale. */
fun foldText(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        if (c.code < 128) sb.append(c.lowercaseChar())
        else sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
    }
    return sb.toString()
}

/** Parole della ricerca, già senza accenti e minuscole. */
fun searchWords(query: String): List<String> =
    foldText(query).split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

/** Parola trovata nel testo: posizione e lunghezza (per evidenziarla). */
class WordHit(val start: Int, val len: Int)

/** Una parola con al massimo un errore (lettera sbagliata, mancante, in più o scambiata). */
private fun withinOneEdit(a: String, b: String): Boolean {
    if (a == b) return true
    val la = a.length
    val lb = b.length
    if (abs(la - lb) > 1) return false
    var i = 0
    while (i < la && i < lb && a[i] == b[i]) i++
    if (la == lb) {
        if (i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)) return true
        return a.substring(i + 1) == b.substring(i + 1)
    }
    return if (la > lb) a.substring(i + 1) == b.substring(i) else b.substring(i + 1) == a.substring(i)
}

/**
 * Cerca [word] in [text] (entrambi già "fold"). Prima il testo esatto; per parole di almeno 5 lettere
 * tollera un errore, utile per gli sbagli dell'OCR ("nesuno" trova "nessuno").
 */
fun findWord(text: String, word: String): WordHit? {
    val i = text.indexOf(word)
    if (i >= 0) return WordHit(i, word.length)
    if (word.length < 5) return null
    var start = -1
    for (j in 0..text.length) {
        val inWord = j < text.length && text[j].isLetterOrDigit()
        if (inWord) {
            if (start < 0) start = j
        } else if (start >= 0) {
            if (abs((j - start) - word.length) <= 1 && withinOneEdit(text.substring(start, j), word)) {
                return WordHit(start, j - start)
            }
            start = -1
        }
    }
    return null
}

/** Vero se ogni parola compare in almeno uno dei testi (già "fold"), non per forza nello stesso. */
fun matchesAll(words: List<String>, texts: List<String>): Boolean =
    words.all { w -> texts.any { findWord(it, w) != null } }
