package com.scanner.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private enum class Step { DATE, TIME, EXACT }

/**
 * Sceglie data e ora del promemoria di un allegato: prima il calendario, poi l'orologio.
 * Chiede il permesso per le notifiche (Android 13+) all'apertura e, se servono,
 * propone le impostazioni "Sveglie e promemoria" per un avviso puntuale (Android 12+).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(vm: MainViewModel, photo: Photo, onDone: () -> Unit) {
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Senza il permesso per le notifiche il promemoria non verrà mostrato", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.notificationsAllowed(context)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // proposta: il promemoria esistente, altrimenti domani alle 9:00
    val initial = remember(photo.id) {
        photo.reminderAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
            ?: LocalDate.now().plusDays(1).atTime(9, 0)
    }
    var step by remember { mutableStateOf(Step.DATE) }
    var date by remember { mutableStateOf(initial.toLocalDate()) }

    when (step) {
        Step.DATE -> {
            val today = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            // il DatePicker lavora con la mezzanotte UTC del giorno scelto
            val state = rememberDatePickerState(
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
                },
            )
            DatePickerDialog(
                onDismissRequest = onDone,
                confirmButton = {
                    TextButton({
                        state.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            step = Step.TIME
                        }
                    }, enabled = state.selectedDateMillis != null) { Text("Avanti") }
                },
                dismissButton = { TextButton(onDone) { Text("Annulla") } },
            ) { DatePicker(state, title = { Text("Promemoria: giorno", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
        }

        Step.TIME -> {
            val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Promemoria: ora") },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton({
                        val at = LocalDateTime.of(date, LocalTime.of(state.hour, state.minute))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        if (at <= System.currentTimeMillis()) {
                            Toast.makeText(context, "Scegli un'ora futura", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        vm.setReminder(photo, at)
                        Toast.makeText(context, "Promemoria il ${Reminders.format(at)}", Toast.LENGTH_SHORT).show()
                        if (Reminders.canScheduleExact(context)) onDone() else step = Step.EXACT
                    }) { Text("Imposta") }
                },
                dismissButton = { TextButton({ step = Step.DATE }) { Text("Indietro") } },
            )
        }

        Step.EXACT -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Avviso puntuale") },
            text = {
                Text(
                    "Il promemoria è salvato, ma senza il permesso \"Sveglie e promemoria\" Android " +
                        "può mostrarlo con qualche minuto di ritardo. Vuoi attivarlo?"
                )
            },
            confirmButton = {
                TextButton({
                    onDone()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) { Text("Apri impostazioni") }
            },
            dismissButton = { TextButton(onDone) { Text("Non ora") } },
        )
    }
}
