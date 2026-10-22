/*
 * Panoptes – IPC-Client zum DiLink-5-Kamera-Hilfsprozess (fast_cam_capture).
 * SPDX-License-Identifier: MIT
 *
 * Clean-Room-Neufassung (0.1.9) nur nach CLEANROOM_FASTCAM_SPEC.md; eigene Namen, eigener Aufbau.
 *
 * Ablauf: verbinden (abstrakter Unix-Socket, Partner-PID prüfen, Begrüßung + Puffer-Deskriptoren empfangen und
 * nur lesend einblenden) → warteAufBild / gibZurueck im Wechsel → trennen.
 * Thread-Modell: ein Lese-Thread (verbinden, warteAufBild, trennen); gibZurueck darf zusätzlich aus einem anderen
 * Thread kommen. Scheitert dort das Senden, wird die Verbindung nur als kaputt markiert und abgeschaltet
 * (shutdown); aufräumen tut der Lese-Thread mit trennen().
 */
#ifndef PKAM_CLIENT_H
#define PKAM_CLIENT_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

#include "pkam_protokoll.h"

/** Ein empfangenes Bild. puffer_fd und pixel gehören weiter dem Client (nicht schließen, nicht freigeben). */
struct KameraBild {
    uint32_t kamera_id = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
    uint64_t puffer_bytes = 0;
    uint32_t puffer_index = 0;   // innerhalb der Kamera
    uint32_t puffer_platz = 0;   // in der Gesamtliste, 0..PKAM_MAX_PUFFER-1
    uint32_t bildnummer = 0;
    uint64_t zeit_ns = 0;
    int puffer_fd = -1;
    const uint8_t* pixel = nullptr;
};

class KameraClient {
public:
    KameraClient();
    ~KameraClient();
    KameraClient(const KameraClient&) = delete;
    KameraClient& operator=(const KameraClient&) = delete;

    /** Trennt zuerst eine alte Verbindung. Name mit führendem '@' (abstrakter Namensraum). */
    bool verbinden(const char* socket_name, int erwartete_pid);
    /** Schließt alles, beliebig oft aufrufbar. */
    void trennen();
    bool istVerbunden() const;
    /** Begrüßung hatte Bit 31 (Rückgabe mit Fence). */
    bool kannRueckgabe() const;
    /** false = kein Bild; danach istVerbunden() prüfen (false → neu verbinden). */
    bool warteAufBild(KameraBild* bild, int wartezeit_ms);
    /** fence_fd oder −1; der Fence bleibt beim Aufrufer. Ohne Rückgabe-Protokoll: nichts tun, true. */
    bool gibZurueck(const KameraBild* bild, int fence_fd);

    // für Tests
    static constexpr int kBegruessungMs = 2000;
    static constexpr int kBildRestMs = 1000;

private:
    bool begruessungEmpfangen();
    bool begruessungPruefen(int deskriptoren) const;
    bool einblenden();
    void aufraeumenLocked();

    mutable std::mutex mutex_;
    int socket_ = -1;
    std::atomic<bool> verbunden_{false};
    bool rueckgabe_ = false;
    pkam_begruessung_t begruessung_{};
    int fds_[PKAM_MAX_PUFFER];
    void* abbild_[PKAM_MAX_PUFFER];
    size_t abbild_bytes_[PKAM_MAX_PUFFER];
    int fd_anzahl_ = 0;
    bool ausstehend_ = false;
    uint32_t aus_kamera_ = 0, aus_index_ = 0, aus_nummer_ = 0;
};

#endif  // PKAM_CLIENT_H
