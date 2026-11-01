// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DogsTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val labels = EmergencyLabels("NOTFALL", "Rasse", "Besitzer", "Kontakt", "Chip", "Tierarzt", "Medizinisch", "Versicherung")

    private fun dog(name: String = "Bella", health: List<HealthItem> = emptyList()) = DogProfile(
        id = "d-$name", name = name, breed = "Mischling", sex = DogSex.FEMALE, neutered = true,
        birthDate = LocalDate.of(2020, 8, 15), weightKg = 24.5, chip = "276 098 1234 5678",
        ownerName = "Max Muster", ownerPhone = "+49 170 1234567", emergencyContact = "Erika, 0301 234",
        vetName = "Dr. Klein", vetPhone = "0301 999", insurance = "Haftpflicht X", medical = "Allergie: Huhn",
        health = health,
    )

    @Test fun ageInYearsAndMonths() {
        assertEquals(DogAge(6, 2), DogLogic.age(LocalDate.of(2020, 8, 15), today))
        assertEquals(DogAge(0, 3), DogLogic.age(LocalDate.of(2026, 7, 20), today))
        assertNull(DogLogic.age(null, today))
        assertNull(DogLogic.age(today.plusDays(1), today))
    }

    @Test fun dueFromIntervalOrFixedDate() {
        val a = HealthItem("1", HealthKind.VACCINATION, "Tollwut", lastDate = LocalDate.of(2025, 11, 10), intervalMonths = 12)
        assertEquals(LocalDate.of(2026, 11, 10), DogLogic.dueOf(a))
        val b = a.copy(dueDate = LocalDate.of(2026, 12, 1))
        assertEquals(LocalDate.of(2026, 12, 1), DogLogic.dueOf(b))
        assertNull(DogLogic.dueOf(HealthItem("2", HealthKind.OTHER, "x", lastDate = today)))
        assertNull(DogLogic.dueOf(HealthItem("3", HealthKind.OTHER, "x", intervalMonths = 3)))
    }

    @Test fun statesAndUpcomingOrder() {
        val items = listOf(
            HealthItem("a", HealthKind.DEWORMING, "Wurmkur", dueDate = today.minusDays(3)),
            HealthItem("b", HealthKind.TICK_FLEA, "Zeckenschutz", dueDate = today.plusDays(10)),
            HealthItem("c", HealthKind.VACCINATION, "Impfung", dueDate = today.plusDays(90)),
            HealthItem("d", HealthKind.OTHER, "ohne Datum"),
        )
        val d = dog(health = items)
        val all = DogLogic.dueEntries(d, today)
        assertEquals(3, all.size)
        assertEquals(DueState.OVERDUE, all[0].state)
        assertEquals(-3, all[0].daysUntil)
        assertEquals(DueState.SOON, all[1].state)
        assertEquals(DueState.LATER, all[2].state)
        val up = DogLogic.upcoming(listOf(d, dog("Anton", listOf(items[1]))), today)
        assertEquals(listOf("a", "b", "b"), up.map { it.item.id })
        assertEquals(DueState.SOON, DogLogic.stateOf(0))
        assertEquals(DueState.SOON, DogLogic.stateOf(30))
        assertEquals(DueState.LATER, DogLogic.stateOf(31))
    }

    @Test fun markDoneMovesNextDue() {
        val item = HealthItem("1", HealthKind.DEWORMING, "Wurmkur", lastDate = today.minusMonths(4), intervalMonths = 3)
        val done = DogLogic.markDone(item, today)
        assertEquals(today.plusMonths(3), DogLogic.dueOf(done))
        val once = HealthItem("2", HealthKind.VET_CHECK, "Kontrolle", dueDate = today)
        assertNull(DogLogic.dueOf(DogLogic.markDone(once, today)))
    }

    @Test fun upsertRemoveAndLimits() {
        var data = DogData()
        data = DogLogic.upsert(data, dog("A"))
        data = DogLogic.upsert(data, dog("A").copy(breed = "Pudel"))
        assertEquals(1, data.dogs.size)
        assertEquals("Pudel", data.dogs[0].breed)
        for (i in 1..20) data = DogLogic.upsert(data, dog("H$i"))
        assertEquals(DogLogic.MAX_DOGS, data.dogs.size)
        assertEquals(DogLogic.MAX_DOGS - 1, DogLogic.remove(data, "d-A").dogs.size)
        val withItem = DogLogic.upsertItem(dog(), HealthItem("x", HealthKind.OTHER, "t"))
        assertEquals(1, DogLogic.removeItem(withItem, "y").health.size)
        assertEquals(0, DogLogic.removeItem(withItem, "x").health.size)
    }

    @Test fun mergeSkipsDuplicatesByChipOrNameAndBirth() {
        val existing = DogData(listOf(dog("Bella")))
        val sameChip = dog("Anders").copy(chip = "276098123 45678")
        val sameName = dog("bella")
        val fresh = dog("Luna").copy(chip = "", birthDate = null)
        val r = DogLogic.merge(existing, listOf(sameChip, sameName, fresh))
        assertEquals(1, r.added)
        assertEquals(2, r.skipped)
        assertEquals(2, r.data.dogs.size)
        assertNotEquals("d-Luna", r.data.dogs[1].id)
    }

    private fun assertNotEquals(a: String, b: String) = assertFalse(a == b)

    @Test fun emergencyTextOrderAndFields() {
        val text = DogLogic.emergencyText(dog(), labels)
        val lines = text.lines()
        assertEquals("NOTFALL", lines[0])
        assertEquals("Bella", lines[1])
        assertTrue(lines[2].startsWith("Besitzer: Max Muster, +49"))
        assertTrue(text.contains("Chip: 276"))
        assertFalse(text.contains("Versicherung"))
        val only = DogLogic.emergencyText(dog().copy(emergencyFields = setOf(EmergencyField.OWNER)), labels)
        assertEquals(3, only.lines().size)
        val withInsurance = DogLogic.emergencyText(dog().copy(emergencyFields = EmergencyField.entries.toSet()), labels)
        assertTrue(withInsurance.contains("Versicherung: Haftpflicht X"))
    }

    @Test fun emergencyTextRespectsLimit() {
        val d = dog().copy(medical = "x".repeat(600), insurance = "kurz", emergencyFields = EmergencyField.entries.toSet())
        val text = DogLogic.emergencyText(d, labels, 300)
        assertTrue(text.length <= 300)
        assertFalse(text.contains("xxxx"))
        assertTrue(text.contains("Besitzer"))
        assertTrue(text.contains("Versicherung: kurz"))
        assertEquals("Bella\nA: b", DogLogic.emergencyText(DogProfile("1", "Bella", ownerName = "b", emergencyFields = setOf(EmergencyField.OWNER)), labels.copy(title = "", owner = "A")).trim())
    }

    @Test fun codecRoundTrip() {
        val d = dog(health = listOf(HealthItem("h1", HealthKind.VACCINATION, "Tollwut \"X\"\nZeile", LocalDate.of(2025, 11, 10), 12, LocalDate.of(2026, 11, 10))))
            .copy(birthApprox = true, emergencyFields = setOf(EmergencyField.OWNER, EmergencyField.VET))
        val data = DogData(listOf(d, dog("Anton").copy(neutered = null, birthDate = null, weightKg = null)))
        val back = DogCodec.decode(DogCodec.encode(data))
        assertEquals(data, back)
    }

    @Test fun codecSanitizesInput() {
        val json = """{"dogs": [
            {"id": "a", "name": "  Rex ", "sex": "male", "weightKg": 900, "birth": "1900-01-01", "chip": "${"9".repeat(100)}",
             "health": [{"id": "h", "title": "ok", "interval": 500, "last": "kein-datum"}, {"id": "h", "title": "doppelt"}, {"title": ""}]},
            {"id": "a", "name": "Zwei"},
            {"name": ""},
            "kein objekt"
        ]}"""
        val data = DogCodec.decode(json)
        assertEquals(2, data.dogs.size)
        val rex = data.dogs[0]
        assertEquals("Rex", rex.name)
        assertNull(rex.weightKg)
        assertNull(rex.birthDate)
        assertEquals(40, rex.chip.length)
        assertEquals(2, rex.health.size)
        assertNull(rex.health[0].intervalMonths)
        assertNull(rex.health[0].lastDate)
        assertFalse(rex.health[0].id == rex.health[1].id)
        assertFalse(data.dogs[0].id == data.dogs[1].id)
        assertEquals(EmergencyField.DEFAULT, rex.emergencyFields)
        assertEquals(0, DogCodec.decode("kaputt{").dogs.size)
        assertEquals(0, DogCodec.decode(null).dogs.size)
    }

    @Test fun codecLimitsDogCount() {
        val json = "{\"dogs\": [" + (1..30).joinToString(",") { "{\"id\": \"i$it\", \"name\": \"H$it\"}" } + "]}"
        assertEquals(DogLogic.MAX_DOGS, DogCodec.decode(json).dogs.size)
    }

    // ---- Import aus der HundeWildnis-App -------------------------------------------------------

    @Test fun importBirthFormats() {
        assertEquals(LocalDate.of(2020, 3, 5) to false, HundeWildnisImport.parseBirth("05.03.2020"))
        assertEquals(LocalDate.of(2020, 3, 1) to true, HundeWildnisImport.parseBirth("03.2020"))
        assertEquals(LocalDate.of(2020, 1, 1) to true, HundeWildnisImport.parseBirth("2020"))
        assertNull(HundeWildnisImport.parseBirth("31.02.2020"))
        assertNull(HundeWildnisImport.parseBirth("irgendwann"))
        assertNull(HundeWildnisImport.parseBirth(""))
    }

    @Test fun importSplitsPhoneAndWeight() {
        assertEquals("Dr. Meier" to "0123 456789", HundeWildnisImport.splitPhone("Dr. Meier, 0123 456789"))
        assertEquals("Max Muster" to "", HundeWildnisImport.splitPhone("Max Muster"))
        assertEquals("" to "+49 170 1234567", HundeWildnisImport.splitPhone("+49 170 1234567"))
        assertEquals(25.5, HundeWildnisImport.parseWeight("25,5 kg")!!, 0.001)
        assertEquals(24.0, HundeWildnisImport.parseWeight("24000 g")!!, 0.001)
        assertNull(HundeWildnisImport.parseWeight("viel"))
        assertNull(HundeWildnisImport.parseWeight("900"))
    }

    private val fullBackup = """{
      "version": "11.0", "backupVersion": 9, "timestamp": 1,
      "dogs": [
        {"id": "x1", "name": "Bella", "breed": "Border Collie", "gender": "female", "neutered": "yes", "birthDate": "03.2020",
         "color": "schwarz-weiß", "chipNumber": "276098123456789", "weight": "19,5 kg", "veterinarian": "Dr. Klein, 0301 999",
         "owner": "Max Muster 0170 1234567", "additionalEmergencyContact": "Erika 0301 234", "healthInsurance": "Krankenschutz X",
         "liabilityInsurance": "Haftpflicht Y", "medicalNotes": "Allergie Huhn", "vaccinationStatus": "Tollwut 10.11.2025",
         "lastDeworming": "im Frühjahr", "lastFleaTick": "01.10.2026", "photoUri": "content://x", "vitalRecords": [], "heatCycles": []},
        {"name": "", "breed": "leer"},
        {"id": "x2", "name": "Rex", "gender": "Rüde", "neutered": "Nein"}
      ],
      "settings": {}, "photoMapping": {}
    }"""

    @Test fun importFullBackup() {
        val dogs = HundeWildnisImport.parse(fullBackup)
        assertEquals(2, dogs.size)
        val b = dogs[0]
        assertEquals("Bella", b.name)
        assertEquals(DogSex.FEMALE, b.sex)
        assertEquals(true, b.neutered)
        assertEquals(LocalDate.of(2020, 3, 1), b.birthDate)
        assertTrue(b.birthApprox)
        assertEquals(19.5, b.weightKg!!, 0.001)
        assertEquals("Dr. Klein", b.vetName)
        assertEquals("0301 999", b.vetPhone)
        assertEquals("Max Muster", b.ownerName)
        assertEquals("0170 1234567", b.ownerPhone)
        assertEquals("Krankenschutz X; Haftpflicht Y", b.insurance)
        assertEquals(3, b.health.size)
        val vacc = b.health.first { it.kind == HealthKind.VACCINATION }
        assertEquals(LocalDate.of(2025, 11, 10), vacc.lastDate)
        assertNull(DogLogic.dueOf(vacc))
        val worm = b.health.first { it.kind == HealthKind.DEWORMING }
        assertNull(worm.lastDate)
        assertTrue(worm.title.contains("im Frühjahr"))
        assertEquals(DogSex.MALE, dogs[1].sex)
        assertEquals(false, dogs[1].neutered)
        assertNotNull(dogs[1].id)
        assertFalse(dogs[0].id == "x1")
    }

    @Test fun importSelectiveBackupAndErrors() {
        val dogs = HundeWildnisImport.parse("""{"version": "11", "includedTypes": ["PROFILES"], "data": {"profiles": [{"name": "Luna"}]}}""")
        assertEquals(listOf("Luna"), dogs.map { it.name })
        try { HundeWildnisImport.parse("""{"foo": 1}"""); throw AssertionError("erwartet") } catch (e: HundeWildnisImport.ImportException) { }
        try { HundeWildnisImport.parse("kein json"); throw AssertionError("erwartet") } catch (e: HundeWildnisImport.ImportException) { }
    }
}
