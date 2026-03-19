package com.example.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditTrailTest {

    @Test
    @DisplayName("L'audit normalise les valeurs vides et protege sa copie")
    void normalizesBlankValuesAndReturnsCopy() {
        AuditTrail auditTrail = new AuditTrail(2);

        auditTrail.record(null, " ", "", "\t", null, null);

        List<AuditTrail.AuditEvent> snapshot = auditTrail.snapshot();
        AuditTrail.AuditEvent event = snapshot.get(0);

        assertEquals("system", event.actor());
        assertEquals("system", event.channel());
        assertEquals("system", event.action());
        assertEquals("system", event.target());
        assertEquals("system", event.outcome());
        assertEquals("", event.detail());

        snapshot.clear();
        assertEquals(1, auditTrail.size());
    }

    @Test
    @DisplayName("L'audit conserve les evenements les plus recents")
    void keepsMostRecentEntries() {
        AuditTrail auditTrail = new AuditTrail(1);

        for (int i = 0; i < 12; i++) {
            auditTrail.record("actor-" + i, "WEB", "ACTION", "target-" + i, "SUCCESS", "detail-" + i);
        }

        List<AuditTrail.AuditEvent> snapshot = auditTrail.snapshot();

        assertEquals(10, snapshot.size());
        assertEquals("actor-11", snapshot.get(0).actor());
        assertEquals("actor-2", snapshot.get(snapshot.size() - 1).actor());
    }
}
