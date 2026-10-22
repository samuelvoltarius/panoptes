/*
 * Panoptes – Drahtformat zum DiLink-5-Kamera-Hilfsprozess (fast_cam_capture).
 * SPDX-License-Identifier: MIT
 *
 * Clean-Room-Neufassung (0.1.9), geschrieben nur nach CLEANROOM_FASTCAM_SPEC.md (Schnittstellenbeschreibung in
 * eigenen Worten). Alle Namen hier sind eigene. Little-endian, lückenlos (gepackt).
 */
#ifndef PKAM_PROTOKOLL_H
#define PKAM_PROTOKOLL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Kennung am Anfang jeder Nachricht: ASCII „FCAM“ als little-endian-Zahl. */
#define PKAM_KENNUNG 0x4643414Du

/* Nachrichtentypen (untere 16 Bit des Typfelds). */
#define PKAM_TYP_MASKE 0xFFFFu
#define PKAM_TYP_BEGRUESSUNG_ANFRAGE 1u /* vom Client nicht benutzt */
#define PKAM_TYP_BEGRUESSUNG 2u
#define PKAM_TYP_BILD 3u
#define PKAM_TYP_RUECKGABE 4u

/* Fähigkeit in der Begrüßung: Server quittiert zurückgegebene Puffer per Fence. */
#define PKAM_FAEHIG_RUECKGABE 0x80000000u

#define PKAM_MAX_KAMERAS 8
#define PKAM_MAX_PUFFER_JE_KAMERA 5
#define PKAM_MAX_PUFFER 40

/* Ein Kamera-Eintrag (28 Byte). */
typedef struct __attribute__((packed)) {
    uint32_t kamera_id;
    uint32_t breite;
    uint32_t hoehe;
    uint32_t zeilenabstand;
    uint32_t puffer_bytes;
    uint32_t puffer_anzahl;
    uint32_t erster_puffer;
} pkam_kamera_t;

/* Begrüßung Server → Client (240 Byte), Puffer-Deskriptoren als SCM_RIGHTS. */
typedef struct __attribute__((packed)) {
    uint32_t kennung;
    uint32_t typ;
    uint32_t kameras;
    uint32_t puffer_gesamt;
    pkam_kamera_t kamera[PKAM_MAX_KAMERAS];
} pkam_begruessung_t;

/* Bild bereit, Server → Client (36 Byte). */
typedef struct __attribute__((packed)) {
    uint32_t kennung;
    uint32_t typ;
    uint32_t kamera_id;
    uint32_t puffer_index;
    uint32_t bildnummer;
    uint32_t breite;
    uint32_t hoehe;
    uint64_t zeit_ns;
} pkam_bild_t;

/* Bild zurückgeben, Client → Server (20 Byte), optional ein Fence-Deskriptor als SCM_RIGHTS. */
typedef struct __attribute__((packed)) {
    uint32_t kennung;
    uint32_t typ;
    uint32_t kamera_id;
    uint32_t puffer_index;
    uint32_t bildnummer;
} pkam_rueckgabe_t;

#ifdef __cplusplus
}
static_assert(sizeof(pkam_kamera_t) == 28, "Kamera-Eintrag 28 Byte");
static_assert(sizeof(pkam_begruessung_t) == 240, "Begruessung 240 Byte");
static_assert(sizeof(pkam_bild_t) == 36, "Bild 36 Byte");
static_assert(sizeof(pkam_rueckgabe_t) == 20, "Rueckgabe 20 Byte");
#else
_Static_assert(sizeof(pkam_kamera_t) == 28, "Kamera-Eintrag 28 Byte");
_Static_assert(sizeof(pkam_begruessung_t) == 240, "Begruessung 240 Byte");
_Static_assert(sizeof(pkam_bild_t) == 36, "Bild 36 Byte");
_Static_assert(sizeof(pkam_rueckgabe_t) == 20, "Rueckgabe 20 Byte");
#endif

#endif /* PKAM_PROTOKOLL_H */
