package com.whispercpp.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() = CpuInfo.getHighPerfCpuCount().coerceAtLeast(2)
}

private class CpuInfo(private val lines: List<String>) {
    private fun getHighPerfCpuCount(): Int =
        try {
            getHighPerfCpuCountByFrequencies()
        } catch (exception: Exception) {
            Log.d(LOG_TAG, "Unable to read CPU frequencies", exception)
            getHighPerfCpuCountByVariant()
        }

    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues("processor") { getMaxCpuFrequency(it.toInt()) }
            .also { Log.d(LOG_TAG, "Binned CPU frequencies: ${it.binnedValues()}") }
            .countDroppingMin()

    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues("CPU variant") { it.substringAfter("0x").toInt(16) }
            .also { Log.d(LOG_TAG, "Binned CPU variants: ${it.binnedValues()}") }
            .countKeepingMin()

    private fun getCpuValues(
        property: String,
        mapper: (String) -> Int
    ): List<Int> =
        lines.asSequence()
            .filter { it.startsWith(property) }
            .map { mapper(it.substringAfter(':').trim()) }
            .sorted()
            .toList()

    private fun List<Int>.binnedValues() = groupingBy { it }.eachCount()

    private fun List<Int>.countDroppingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it > min }
    }

    private fun List<Int>.countKeepingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it == min }
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        fun getHighPerfCpuCount(): Int =
            try {
                readCpuInfo().getHighPerfCpuCount()
            } catch (exception: Exception) {
                Log.d(LOG_TAG, "Unable to read /proc/cpuinfo", exception)
                (Runtime.getRuntime().availableProcessors() - 4).coerceAtLeast(0)
            }

        private fun readCpuInfo() =
            CpuInfo(
                BufferedReader(FileReader("/proc/cpuinfo")).useLines { it.toList() }
            )

        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
            val maxFreq = BufferedReader(FileReader(path)).use { it.readLine() }
            return maxFreq.toInt()
        }
    }
}
