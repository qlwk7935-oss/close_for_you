package com.team.popup.experiment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 실험 중 일어난 일(팝업 표시, 버튼 선택)을 메모리에만 기록한다.
 * 화면 글자나 개인정보는 넣지 않는다 (절대 원칙 6). 앱이 꺼지면 사라진다.
 */
object ExperimentLog {
    sealed interface Entry {
        val time: String

        data class Shown(override val time: String, val mode: AnnounceMode) : Entry

        data class Chosen(override val time: String, val choice: Choice) : Entry
    }

    private const val MAX_ENTRIES = 20
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun shown(mode: AnnounceMode) = add(Entry.Shown(now(), mode))

    fun chosen(choice: Choice) = add(Entry.Chosen(now(), choice))

    private fun now(): String = LocalTime.now().format(timeFormat)

    private fun add(entry: Entry) = _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
}
