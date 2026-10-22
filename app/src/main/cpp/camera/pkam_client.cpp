/*
 * Panoptes – IPC-Client zum DiLink-5-Kamera-Hilfsprozess (fast_cam_capture).
 * SPDX-License-Identifier: MIT
 *
 * Clean-Room-Neufassung (0.1.9), geschrieben ausschließlich nach CLEANROOM_FASTCAM_SPEC.md und der POSIX-/Linux-
 * Dokumentation (unix(7), cmsg(3), socket(7) SO_PEERCRED, mmap(2), poll(2)). Siehe pkam_client.h.
 */
#include "pkam_client.h"

#include <cerrno>
#include <cstring>
#include <ctime>

#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

int64_t jetztMs() {
    timespec t{};
    if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) return 0;
    return static_cast<int64_t>(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}

/** Wartet bis lesbar; 1 = lesbar, 0 = Zeit abgelaufen, −1 = Fehler/geschlossen. EINTR wird wiederholt. */
int warteLesbar(int fd, int64_t frist_ms) {
    for (;;) {
        int64_t rest = frist_ms - jetztMs();
        if (rest <= 0) return 0;
        pollfd p{};
        p.fd = fd;
        p.events = POLLIN;
        int r = poll(&p, 1, rest > 0x7fffffff ? 0x7fffffff : static_cast<int>(rest));
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) return -1;
        if (r == 0) return 0;
        if (p.revents & (POLLERR | POLLNVAL)) return -1;
        // POLLHUP mit noch lesbaren Daten: lesen lassen; recv meldet dann 0 = geschlossen
        return 1;
    }
}

/** Liest genau n Byte bis zur Frist. true nur bei vollständig. */
bool liesGenau(int fd, void* ziel, size_t n, int64_t frist_ms) {
    size_t da = 0;
    auto* z = static_cast<uint8_t*>(ziel);
    while (da < n) {
        int w = warteLesbar(fd, frist_ms);
        if (w <= 0) return false;
        ssize_t r = recv(fd, z + da, n - da, MSG_DONTWAIT);
        if (r < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (r <= 0) return false;
        da += static_cast<size_t>(r);
    }
    return true;
}

}  // namespace

KameraClient::KameraClient() {
    for (int i = 0; i < PKAM_MAX_PUFFER; i++) {
        fds_[i] = -1;
        abbild_[i] = nullptr;
        abbild_bytes_[i] = 0;
    }
}

KameraClient::~KameraClient() { trennen(); }

bool KameraClient::istVerbunden() const { return verbunden_.load(); }

bool KameraClient::kannRueckgabe() const {
    std::lock_guard<std::mutex> l(mutex_);
    return verbunden_.load() && rueckgabe_;
}

void KameraClient::trennen() {
    std::lock_guard<std::mutex> l(mutex_);
    aufraeumenLocked();
}

void KameraClient::aufraeumenLocked() {
    verbunden_.store(false);
    if (socket_ >= 0) {
        close(socket_);
        socket_ = -1;
    }
    for (int i = 0; i < PKAM_MAX_PUFFER; i++) {
        if (abbild_[i] != nullptr) munmap(abbild_[i], abbild_bytes_[i]);
        abbild_[i] = nullptr;
        abbild_bytes_[i] = 0;
        if (fds_[i] >= 0) close(fds_[i]);
        fds_[i] = -1;
    }
    fd_anzahl_ = 0;
    rueckgabe_ = false;
    ausstehend_ = false;
    std::memset(&begruessung_, 0, sizeof(begruessung_));
}

bool KameraClient::verbinden(const char* socket_name, int erwartete_pid) {
    trennen();
    if (socket_name == nullptr || socket_name[0] != '@' || erwartete_pid <= 0) return false;
    size_t name_laenge = std::strlen(socket_name + 1);
    sockaddr_un adresse{};
    // abstrakt: sun_path[0] = 0, danach der Name ohne abschließendes Null-Byte
    if (name_laenge == 0 || name_laenge > sizeof(adresse.sun_path) - 1) return false;
    adresse.sun_family = AF_UNIX;
    std::memcpy(adresse.sun_path + 1, socket_name + 1, name_laenge);
    socklen_t adresse_laenge =
            static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_laenge);

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    int r;
    do {
        r = connect(fd, reinterpret_cast<sockaddr*>(&adresse), adresse_laenge);
    } while (r < 0 && errno == EINTR);
    if (r != 0) {
        close(fd);
        return false;
    }
    ucred partner{};
    socklen_t partner_laenge = sizeof(partner);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &partner, &partner_laenge) != 0
            || partner_laenge != sizeof(partner)
            || partner.pid != erwartete_pid) {
        close(fd);
        return false;
    }
    {
        std::lock_guard<std::mutex> l(mutex_);
        socket_ = fd;
    }
    if (!begruessungEmpfangen() || !einblenden()) {
        trennen();
        return false;
    }
    verbunden_.store(true);
    return true;
}

bool KameraClient::begruessungEmpfangen() {
    const int64_t frist = jetztMs() + kBegruessungMs;
    auto* ziel = reinterpret_cast<uint8_t*>(&begruessung_);
    size_t da = 0;
    int empfangen = 0;
    while (da < sizeof(begruessung_)) {
        if (warteLesbar(socket_, frist) <= 0) return false;
        iovec iov{};
        iov.iov_base = ziel + da;
        iov.iov_len = sizeof(begruessung_) - da;
        alignas(cmsghdr) char steuer[CMSG_SPACE(sizeof(int) * PKAM_MAX_PUFFER)];
        msghdr nachricht{};
        nachricht.msg_iov = &iov;
        nachricht.msg_iovlen = 1;
        nachricht.msg_control = steuer;
        nachricht.msg_controllen = sizeof(steuer);
        ssize_t r = recvmsg(socket_, &nachricht, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (r < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (r <= 0) return false;
        bool steuer_ok = (nachricht.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) == 0;
        for (cmsghdr* h = CMSG_FIRSTHDR(&nachricht); h != nullptr; h = CMSG_NXTHDR(&nachricht, h)) {
            if (h->cmsg_level != SOL_SOCKET || h->cmsg_type != SCM_RIGHTS
                    || h->cmsg_len < CMSG_LEN(0)
                    || (h->cmsg_len - CMSG_LEN(0)) % sizeof(int) != 0) {
                steuer_ok = false;
                continue;
            }
            size_t anzahl = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            const unsigned char* daten = CMSG_DATA(h);
            for (size_t i = 0; i < anzahl; i++) {
                int d;
                std::memcpy(&d, daten + i * sizeof(int), sizeof(int));
                if (empfangen < PKAM_MAX_PUFFER) {
                    fds_[empfangen] = d;   // zählen auch bei späterem Fehler mit (trennen schließt)
                } else {
                    close(d);
                    steuer_ok = false;
                }
                empfangen++;
            }
        }
        fd_anzahl_ = empfangen > PKAM_MAX_PUFFER ? PKAM_MAX_PUFFER : empfangen;
        if (!steuer_ok) return false;
        da += static_cast<size_t>(r);
    }
    return begruessungPruefen(empfangen);
}

bool KameraClient::begruessungPruefen(int deskriptoren) const {
    const pkam_begruessung_t& b = begruessung_;
    if (b.kennung != PKAM_KENNUNG || (b.typ & PKAM_TYP_MASKE) != PKAM_TYP_BEGRUESSUNG) return false;
    if (b.kameras < 1 || b.kameras > PKAM_MAX_KAMERAS) return false;
    if (b.puffer_gesamt < 1 || b.puffer_gesamt > PKAM_MAX_PUFFER) return false;
    if (deskriptoren < 0 || static_cast<uint32_t>(deskriptoren) != b.puffer_gesamt) return false;
    bool belegt[PKAM_MAX_PUFFER] = {};
    uint32_t abgedeckt = 0;
    for (uint32_t i = 0; i < b.kameras; i++) {
        const pkam_kamera_t& k = b.kamera[i];
        for (uint32_t j = 0; j < i; j++) {
            if (b.kamera[j].kamera_id == k.kamera_id) return false;
        }
        if (k.breite == 0 || k.hoehe == 0) return false;
        if (static_cast<uint64_t>(k.zeilenabstand) < static_cast<uint64_t>(k.breite) * 2) return false;
        if (static_cast<uint64_t>(k.puffer_bytes) < static_cast<uint64_t>(k.zeilenabstand) * k.hoehe) return false;
        if (k.puffer_anzahl < 1 || k.puffer_anzahl > PKAM_MAX_PUFFER_JE_KAMERA) return false;
        uint64_t ende = static_cast<uint64_t>(k.erster_puffer) + k.puffer_anzahl;
        if (ende > b.puffer_gesamt) return false;
        for (uint32_t p = k.erster_puffer; p < ende; p++) {
            if (belegt[p]) return false;   // Überlappung
            belegt[p] = true;
            abgedeckt++;
        }
    }
    return abgedeckt == b.puffer_gesamt;   // keine Lücke
}

bool KameraClient::einblenden() {
    std::lock_guard<std::mutex> l(mutex_);
    const pkam_begruessung_t& b = begruessung_;
    for (uint32_t i = 0; i < b.kameras; i++) {
        const pkam_kamera_t& k = b.kamera[i];
        for (uint32_t p = k.erster_puffer; p < k.erster_puffer + k.puffer_anzahl; p++) {
            void* a = mmap(nullptr, k.puffer_bytes, PROT_READ, MAP_SHARED, fds_[p], 0);
            if (a == MAP_FAILED) return false;
            abbild_[p] = a;
            abbild_bytes_[p] = k.puffer_bytes;
        }
    }
    rueckgabe_ = (b.typ & PKAM_FAEHIG_RUECKGABE) != 0;
    return true;
}

bool KameraClient::warteAufBild(KameraBild* bild, int wartezeit_ms) {
    if (bild == nullptr) return false;
    int fd;
    {
        std::lock_guard<std::mutex> l(mutex_);
        if (!verbunden_.load()) {
            if (socket_ >= 0) aufraeumenLocked();   // nach Sendefehler aus anderem Thread
            return false;
        }
        if (rueckgabe_ && ausstehend_) return false;   // erst das vorige Bild zurückgeben
        fd = socket_;
    }
    int w = warteLesbar(fd, jetztMs() + (wartezeit_ms < 0 ? 0 : wartezeit_ms));
    if (w == 0) return false;   // nichts gekommen, Verbindung bleibt
    pkam_bild_t n{};
    if (w < 0 || !liesGenau(fd, &n, sizeof(n), jetztMs() + kBildRestMs)) {
        trennen();
        return false;
    }
    std::lock_guard<std::mutex> l(mutex_);
    if (!verbunden_.load()) {
        aufraeumenLocked();
        return false;
    }
    const pkam_kamera_t* k = nullptr;
    for (uint32_t i = 0; i < begruessung_.kameras; i++) {
        if (begruessung_.kamera[i].kamera_id == n.kamera_id) k = &begruessung_.kamera[i];
    }
    if (n.kennung != PKAM_KENNUNG || n.typ != PKAM_TYP_BILD || k == nullptr
            || n.puffer_index >= k->puffer_anzahl
            || n.breite != k->breite || n.hoehe != k->hoehe) {
        aufraeumenLocked();
        return false;
    }
    uint32_t platz = k->erster_puffer + n.puffer_index;
    bild->kamera_id = n.kamera_id;
    bild->width = k->breite;
    bild->height = k->hoehe;
    bild->stride = k->zeilenabstand;
    bild->puffer_bytes = k->puffer_bytes;
    bild->puffer_index = n.puffer_index;
    bild->puffer_platz = platz;
    bild->bildnummer = n.bildnummer;
    bild->zeit_ns = n.zeit_ns;
    bild->puffer_fd = fds_[platz];
    bild->pixel = static_cast<const uint8_t*>(abbild_[platz]);
    if (rueckgabe_) {
        ausstehend_ = true;
        aus_kamera_ = n.kamera_id;
        aus_index_ = n.puffer_index;
        aus_nummer_ = n.bildnummer;
    }
    return true;
}

bool KameraClient::gibZurueck(const KameraBild* bild, int fence_fd) {
    std::lock_guard<std::mutex> l(mutex_);
    if (!verbunden_.load() || bild == nullptr) return false;
    if (!rueckgabe_) return true;
    if (!ausstehend_ || bild->kamera_id != aus_kamera_ || bild->puffer_index != aus_index_
            || bild->bildnummer != aus_nummer_) {
        return false;   // passt nicht zum ausstehenden Bild – kein Trennen
    }
    pkam_rueckgabe_t m{};
    m.kennung = PKAM_KENNUNG;
    m.typ = PKAM_TYP_RUECKGABE;
    m.kamera_id = aus_kamera_;
    m.puffer_index = aus_index_;
    m.bildnummer = aus_nummer_;
    iovec iov{};
    iov.iov_base = &m;
    iov.iov_len = sizeof(m);
    msghdr nachricht{};
    nachricht.msg_iov = &iov;
    nachricht.msg_iovlen = 1;
    alignas(cmsghdr) char steuer[CMSG_SPACE(sizeof(int))];
    if (fence_fd >= 0) {
        std::memset(steuer, 0, sizeof(steuer));
        nachricht.msg_control = steuer;
        nachricht.msg_controllen = sizeof(steuer);
        cmsghdr* h = CMSG_FIRSTHDR(&nachricht);
        h->cmsg_level = SOL_SOCKET;
        h->cmsg_type = SCM_RIGHTS;
        h->cmsg_len = CMSG_LEN(sizeof(int));
        std::memcpy(CMSG_DATA(h), &fence_fd, sizeof(int));
    }
    ssize_t r;
    do {
        r = sendmsg(socket_, &nachricht, MSG_NOSIGNAL);
    } while (r < 0 && errno == EINTR);
    if (r != static_cast<ssize_t>(sizeof(m))) {
        // Nur abschalten; der Lese-Thread räumt mit trennen()/warteAufBild auf.
        verbunden_.store(false);
        shutdown(socket_, SHUT_RDWR);
        return false;
    }
    ausstehend_ = false;
    return true;
}
