package org.mbali.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.mbali.model.ProcessSnapshot;

class ProcessReaderTest {

    @Test
    void testLigneCompleteEtEnTeteIgnore() {
        String csv = "\"ProcessId\",\"ParentProcessId\",\"Name\",\"CreationDate\",\"WorkingSetSize\",\"KernelModeTime\",\"UserModeTime\"\n" +
                     "\"804\",\"4\",\"smss.exe\",\"2026-09-13T04:58:05.2718220Z\",\"1515520\",\"2187500\",\"468750\"";
        
        List<ProcessSnapshot> result = ProcessReader.parse(csv);
        
        assertEquals(1, result.size(), "L'en-tête doit être ignoré");
        ProcessSnapshot p = result.get(0);
        
        assertEquals(804, p.pid());
        assertEquals(4, p.parentPid().getAsLong());
        assertEquals("smss.exe", p.name());
        assertEquals(Instant.parse("2026-09-13T04:58:05.2718220Z"), p.startTime().get());
        assertEquals(1515520, p.memoryBytes().getAsLong());
        // (2187500 + 468750) * 100
        assertEquals(Duration.ofNanos(265625000L), p.cpuTime().get()); 
    }

    @Test
    void testChampMemoireVide() {
        String csv = "Header\n" +
                     "\"100\",\"0\",\"Test\",\"2026-09-13T04:58:00Z\",\"\",\"100\",\"200\"";
        
        List<ProcessSnapshot> result = ProcessReader.parse(csv);
        
        assertEquals(1, result.size());
        assertTrue(result.get(0).memoryBytes().isEmpty(), "La mémoire vide ne doit pas renvoyer 0");
    }

    @Test
    void testPidIllisibleIgnoreLigne() {
        String csv = "Header\n" +
                     "\"abc\",\"4\",\"Plante\",\"\",\"1000\",\"100\",\"200\"\n" +
                     "\"123\",\"4\",\"Survit\",\"\",\"1000\",\"100\",\"200\"";
        
        List<ProcessSnapshot> result = ProcessReader.parse(csv);
        
        assertEquals(1, result.size(), "La ligne avec PID 'abc' doit être totalement ignorée");
        assertEquals(123, result.get(0).pid());
        assertEquals("Survit", result.get(0).name());
    }

    @Test
    void testDateMalFormee() {
        String csv = "Header\n" +
                     "\"500\",\"4\",\"TestDate\",\"pas-une-date\",\"1000\",\"100\",\"200\"";
        
        List<ProcessSnapshot> result = ProcessReader.parse(csv);
        
        assertEquals(1, result.size());
        assertTrue(result.get(0).startTime().isEmpty(), "La date illisible donne un Optional vide");
        assertEquals("TestDate", result.get(0).name(), "Le processus doit quand même être récupéré");
    }

    @Test
    void testVirguleDansLeNom() {
        // Windows autorise les virgules dans les noms de fichiers : un découpage naïf sur
        // "," décalait toutes les colonnes suivantes.
        String csv = "Header\n" +
                     "\"900\",\"4\",\"Foo, Inc. helper.exe\",\"2026-09-13T04:58:00Z\",\"4096\",\"100\",\"200\"";

        List<ProcessSnapshot> result = ProcessReader.parse(csv);

        assertEquals(1, result.size());
        assertEquals("Foo, Inc. helper.exe", result.get(0).name());
        assertEquals(4096, result.get(0).memoryBytes().getAsLong(), "les colonnes ne doivent pas se décaler");
    }

    @Test
    void testValeurNegativeDevientVide() {
        // Une mémoire négative est impossible : elle devient vide au lieu de faire lever
        // une exception par ProcessSnapshot, ce qui interromprait toute la lecture.
        String csv = "Header\n" +
                     "\"901\",\"4\",\"Bizarre\",\"\",\"-12\",\"100\",\"200\"\n" +
                     "\"902\",\"4\",\"Normal\",\"\",\"1000\",\"100\",\"200\"";

        List<ProcessSnapshot> result = ProcessReader.parse(csv);

        assertEquals(2, result.size(), "les deux processus sont gardés");
        assertTrue(result.get(0).memoryBytes().isEmpty());
    }
}