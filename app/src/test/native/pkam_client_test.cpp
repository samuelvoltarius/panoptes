// Panoptes – Tests für den Clean-Room-IPC-Client (pkam_client) gegen einen Server-Nachbau nach
// CLEANROOM_FASTCAM_SPEC.md. SPDX-License-Identifier: MIT
//
// Bauen/laufen (Linux, z. B. WSL):  sh app/src/test/native/run.sh
#include "pkam_client.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

static int g_fehler = 0;
#define PRUEFE(x)                                                                \
    do {                                                                         \
        if (!(x)) {                                                              \
            std::fprintf(stderr, "  FEHLER %s:%d: %s\n", __FILE__, __LINE__, #x); \
            g_fehler++;                                                          \
        }                                                                        \
    } while (0)

namespace {

int memfdMit(size_t bytes, uint8_t muster) {
    int fd = static_cast<int>(syscall(SYS_memfd_create, "pkam-test", 0));
    if (fd < 0) return -1;
    if (ftruncate(fd, static_cast<off_t>(bytes)) != 0) return -1;
    void* a = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    std::memset(a, muster, bytes);
    munmap(a, bytes);
    return fd;
}

struct Server {
    std::string name;   // ohne '@'
    int lausch = -1;
    int kunde = -1;

    explicit Server(const std::string& n) : name(n) {
        lausch = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        sockaddr_un a{};
        a.sun_family = AF_UNIX;
        std::memcpy(a.sun_path + 1, name.data(), name.size());
        socklen_t l = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
        if (bind(lausch, reinterpret_cast<sockaddr*>(&a), l) != 0 || listen(lausch, 1) != 0) {
            std::perror("bind/listen");
            std::exit(2);
        }
    }
    ~Server() {
        if (kunde >= 0) close(kunde);
        if (lausch >= 0) close(lausch);
    }
    bool annehmen(int ms = 2000) {
        pollfd p{lausch, POLLIN, 0};
        if (poll(&p, 1, ms) <= 0) return false;
        kunde = accept4(lausch, nullptr, nullptr, SOCK_CLOEXEC);
        return kunde >= 0;
    }
    // Daten + optionale Deskriptoren senden
    bool senden(const void* daten, size_t n, const std::vector<int>& fds = {}) {
        iovec iov{const_cast<void*>(daten), n};
        msghdr m{};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        std::vector<char> steuer;
        if (!fds.empty()) {
            steuer.assign(CMSG_SPACE(sizeof(int) * fds.size()), 0);
            m.msg_control = steuer.data();
            m.msg_controllen = steuer.size();
            cmsghdr* h = CMSG_FIRSTHDR(&m);
            h->cmsg_level = SOL_SOCKET;
            h->cmsg_type = SCM_RIGHTS;
            h->cmsg_len = CMSG_LEN(sizeof(int) * fds.size());
            std::memcpy(CMSG_DATA(h), fds.data(), sizeof(int) * fds.size());
        }
        return sendmsg(kunde, &m, MSG_NOSIGNAL) == static_cast<ssize_t>(n);
    }
    // Rückgabe lesen: liefert Anzahl Fences (−1 bei Fehler)
    int rueckgabeLesen(pkam_rueckgabe_t* r, int ms = 1000) {
        pollfd p{kunde, POLLIN, 0};
        if (poll(&p, 1, ms) <= 0) return -1;
        iovec iov{r, sizeof(*r)};
        alignas(cmsghdr) char steuer[CMSG_SPACE(sizeof(int) * 2)];
        msghdr m{};
        m.msg_iov = &iov;
        m.msg_iovlen = 1;
        m.msg_control = steuer;
        m.msg_controllen = sizeof(steuer);
        ssize_t n = recvmsg(kunde, &m, 0);
        if (n != static_cast<ssize_t>(sizeof(*r))) return -1;
        int fences = 0;
        for (cmsghdr* h = CMSG_FIRSTHDR(&m); h; h = CMSG_NXTHDR(&m, h)) {
            if (h->cmsg_type == SCM_RIGHTS) {
                int k = static_cast<int>((h->cmsg_len - CMSG_LEN(0)) / sizeof(int));
                for (int i = 0; i < k; i++) {
                    int d;
                    std::memcpy(&d, CMSG_DATA(h) + i * sizeof(int), sizeof(int));
                    close(d);
                }
                fences += k;
            }
        }
        return fences;
    }
};

std::string neuerName() {
    static int z = 0;
    return "pkam-test-" + std::to_string(getpid()) + "-" + std::to_string(++z);
}

// Eine Kamera (id 7, 64x4, Abstand 128, 2 Puffer) + eine zweite (id 9, 32x2, Abstand 64, 3 Puffer)
pkam_begruessung_t begruessung(bool mitRueckgabe) {
    pkam_begruessung_t b{};
    b.kennung = PKAM_KENNUNG;
    b.typ = PKAM_TYP_BEGRUESSUNG | (mitRueckgabe ? PKAM_FAEHIG_RUECKGABE : 0u);
    b.kameras = 2;
    b.puffer_gesamt = 5;
    b.kamera[0] = {7, 64, 4, 128, 512, 2, 0};
    b.kamera[1] = {9, 32, 2, 64, 128, 3, 2};
    return b;
}

std::vector<int> puffer(const pkam_begruessung_t& b) {
    std::vector<int> fds;
    for (uint32_t i = 0; i < b.kameras; i++) {
        for (uint32_t p = 0; p < b.kamera[i].puffer_anzahl; p++) {
            fds.push_back(memfdMit(b.kamera[i].puffer_bytes, static_cast<uint8_t>(0x10 * (i + 1) + p)));
        }
    }
    return fds;
}

void schliessen(std::vector<int>& fds) {
    for (int f : fds) close(f);
    fds.clear();
}

pkam_bild_t bild(uint32_t kamera, uint32_t index, uint32_t nummer, uint32_t w, uint32_t h) {
    pkam_bild_t n{};
    n.kennung = PKAM_KENNUNG;
    n.typ = PKAM_TYP_BILD;
    n.kamera_id = kamera;
    n.puffer_index = index;
    n.bildnummer = nummer;
    n.breite = w;
    n.hoehe = h;
    n.zeit_ns = 123456789ull;
    return n;
}

/** Server-Ablauf im Thread, Client im Haupt-Thread. */
bool verbindeMit(KameraClient& c, const std::string& name, int pid, std::function<void(Server&)> ablauf,
                 std::thread* t, Server** s) {
    *s = new Server(name);
    Server* sp = *s;
    *t = std::thread([sp, ablauf] {
        if (sp->annehmen()) ablauf(*sp);
    });
    return c.verbinden(("@" + name).c_str(), pid);
}

void test(const char* titel, std::function<void()> f) {
    int vorher = g_fehler;
    f();
    std::printf("%s %s\n", g_fehler == vorher ? "ok  " : "ROT ", titel);
}

}  // namespace

int main() {
    const int ich = getpid();

    test("Groessen der Nachrichten", [] {
        PRUEFE(sizeof(pkam_begruessung_t) == 240);
        PRUEFE(sizeof(pkam_bild_t) == 36);
        PRUEFE(sizeof(pkam_rueckgabe_t) == 20);
        PRUEFE(PKAM_KENNUNG == 0x4643414Du);
    });

    test("Gutfall: Begruessung, Bild, Rueckgabe mit Fence, Pixel lesbar", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        std::atomic<int> fences{-2};
        pkam_rueckgabe_t r{};
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            auto fds = puffer(b);
            sv.senden(&b, sizeof(b), fds);
            schliessen(fds);
            auto n = bild(9, 1, 42, 32, 2);
            sv.senden(&n, sizeof(n));
            fences = sv.rueckgabeLesen(&r);
        }, &t, &s);
        PRUEFE(ok);
        PRUEFE(c.istVerbunden());
        PRUEFE(c.kannRueckgabe());
        KameraBild b;
        PRUEFE(c.warteAufBild(&b, 1000));
        PRUEFE(b.kamera_id == 9 && b.puffer_index == 1 && b.puffer_platz == 3 && b.bildnummer == 42);
        PRUEFE(b.width == 32 && b.height == 2 && b.stride == 64 && b.puffer_bytes == 128);
        PRUEFE(b.zeit_ns == 123456789ull && b.puffer_fd >= 0 && b.pixel != nullptr);
        PRUEFE(b.pixel != nullptr && b.pixel[0] == 0x21);   // Kamera 2, Puffer 1
        int fence = memfdMit(1, 0);
        PRUEFE(c.gibZurueck(&b, fence));
        t.join();
        PRUEFE(fences == 1);
        PRUEFE(r.kennung == PKAM_KENNUNG && r.typ == PKAM_TYP_RUECKGABE && r.kamera_id == 9
               && r.puffer_index == 1 && r.bildnummer == 42);
        PRUEFE(fcntl(fence, F_GETFD) >= 0);   // Fence bleibt beim Aufrufer
        close(fence);
        c.trennen();
        c.trennen();   // beliebig oft
        PRUEFE(!c.istVerbunden());
        delete s;
    });

    test("Aufgeteilte Begruessung (3 Teile, Deskriptoren im zweiten)", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            auto fds = puffer(b);
            auto* p = reinterpret_cast<uint8_t*>(&b);
            sv.senden(p, 10);
            usleep(20000);
            sv.senden(p + 10, 100, fds);
            usleep(20000);
            sv.senden(p + 110, 130);
            schliessen(fds);
            usleep(200000);
        }, &t, &s);
        PRUEFE(ok);
        t.join();
        delete s;
    });

    test("Falsche PID wird abgelehnt", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich + 1, [&](Server&) { usleep(100000); }, &t, &s);
        PRUEFE(!ok);
        PRUEFE(!c.istVerbunden());
        t.join();
        delete s;
        PRUEFE(!c.verbinden("@x", 0));       // PID ≤ 0
        PRUEFE(!c.verbinden("ohne-at", ich));
        PRUEFE(!c.verbinden("@", ich));       // leerer Name
        std::string lang(200, 'x');
        PRUEFE(!c.verbinden(("@" + lang).c_str(), ich));
    });

    auto ungueltig = [&](const char* titel, std::function<void(pkam_begruessung_t&)> aendern, int fdDelta = 0) {
        test(titel, [&] {
            KameraClient c;
            std::thread t;
            Server* s;
            bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
                auto b = begruessung(true);
                auto fds = puffer(b);
                aendern(b);
                if (fdDelta < 0) {
                    close(fds.back());
                    fds.pop_back();
                }
                sv.senden(&b, sizeof(b), fds);
                schliessen(fds);
                usleep(100000);
            }, &t, &s);
            PRUEFE(!ok);
            PRUEFE(!c.istVerbunden());
            t.join();
            delete s;
        });
    };
    ungueltig("Doppelte Kamera-ID", [](pkam_begruessung_t& b) { b.kamera[1].kamera_id = 7; });
    ungueltig("Luecke in den Pufferbereichen", [](pkam_begruessung_t& b) { b.kamera[1].erster_puffer = 3; });
    ungueltig("Ueberlappung", [](pkam_begruessung_t& b) {
        b.kamera[1].erster_puffer = 1;
        b.kamera[1].puffer_anzahl = 3;
        b.puffer_gesamt = 5;
    });
    ungueltig("Falsche Kennung", [](pkam_begruessung_t& b) { b.kennung = 0x12345678; });
    ungueltig("Falscher Typ", [](pkam_begruessung_t& b) { b.typ = PKAM_TYP_BILD; });
    ungueltig("Zeilenabstand < Breite*2", [](pkam_begruessung_t& b) { b.kamera[0].zeilenabstand = 100; });
    ungueltig("Puffer zu klein", [](pkam_begruessung_t& b) { b.kamera[0].puffer_bytes = 100; });
    ungueltig("Zu viele Puffer je Kamera", [](pkam_begruessung_t& b) { b.kamera[0].puffer_anzahl = 6; });
    ungueltig("Deskriptoren fehlen", [](pkam_begruessung_t&) {}, -1);

    test("Begruessung unvollstaendig innerhalb 2 s → gescheitert", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            sv.senden(&b, 100);
            usleep(2600000);
        }, &t, &s);
        PRUEFE(!ok);
        t.join();
        delete s;
    });

    test("Bit 31 fehlt: verbunden, aber kannRueckgabe() = false; Rueckgabe ist No-op", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(false);
            auto fds = puffer(b);
            sv.senden(&b, sizeof(b), fds);
            schliessen(fds);
            auto n = bild(7, 0, 1, 64, 4);
            sv.senden(&n, sizeof(n));
            usleep(300000);
        }, &t, &s);
        PRUEFE(ok);
        PRUEFE(!c.kannRueckgabe());
        KameraBild b;
        PRUEFE(c.warteAufBild(&b, 1000));
        PRUEFE(c.gibZurueck(&b, -1));
        t.join();
        delete s;
    });

    test("Wartezeit ohne Daten: kein Bild, Verbindung bleibt", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            auto fds = puffer(b);
            sv.senden(&b, sizeof(b), fds);
            schliessen(fds);
            usleep(500000);
        }, &t, &s);
        PRUEFE(ok);
        KameraBild b;
        PRUEFE(!c.warteAufBild(&b, 100));
        PRUEFE(c.istVerbunden());
        t.join();
        delete s;
    });

    auto bildFehler = [&](const char* titel, std::function<void(Server&)> senden) {
        test(titel, [&] {
            KameraClient c;
            std::thread t;
            Server* s;
            bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
                auto b = begruessung(true);
                auto fds = puffer(b);
                sv.senden(&b, sizeof(b), fds);
                schliessen(fds);
                senden(sv);
                usleep(1500000);
            }, &t, &s);
            PRUEFE(ok);
            KameraBild b;
            PRUEFE(!c.warteAufBild(&b, 1000));
            PRUEFE(!c.istVerbunden());   // Fehler → getrennt, Aufrufer verbindet neu
            t.join();
            delete s;
        });
    };
    bildFehler("Bildnachricht mit falscher Groesse (Breite)", [](Server& sv) {
        auto n = bild(7, 0, 1, 63, 4);
        sv.senden(&n, sizeof(n));
    });
    bildFehler("Unbekannte Kamera", [](Server& sv) {
        auto n = bild(5, 0, 1, 64, 4);
        sv.senden(&n, sizeof(n));
    });
    bildFehler("Pufferindex zu gross", [](Server& sv) {
        auto n = bild(7, 2, 1, 64, 4);
        sv.senden(&n, sizeof(n));
    });
    bildFehler("Typ ungleich 3 (mit Bit 31)", [](Server& sv) {
        auto n = bild(7, 0, 1, 64, 4);
        n.typ |= PKAM_FAEHIG_RUECKGABE;
        sv.senden(&n, sizeof(n));
    });
    bildFehler("Abgeschnittene Bildnachricht (Rest kommt nicht binnen 1 s)", [](Server& sv) {
        auto n = bild(7, 0, 1, 64, 4);
        sv.senden(&n, 20);
    });
    bildFehler("Server schliesst mitten im Lauf", [](Server& sv) {
        close(sv.kunde);
        sv.kunde = -1;
    });

    test("Zweites Bild vor der Rueckgabe wird nicht ausgeliefert; falsche Rueckgabe trennt nicht", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        std::atomic<int> fences{-2};
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            auto fds = puffer(b);
            sv.senden(&b, sizeof(b), fds);
            schliessen(fds);
            auto n1 = bild(7, 0, 1, 64, 4);
            auto n2 = bild(7, 1, 2, 64, 4);
            sv.senden(&n1, sizeof(n1));
            sv.senden(&n2, sizeof(n2));
            pkam_rueckgabe_t r{};
            fences = sv.rueckgabeLesen(&r, 2000);
            usleep(300000);
        }, &t, &s);
        PRUEFE(ok);
        KameraBild a;
        PRUEFE(c.warteAufBild(&a, 1000));
        KameraBild b2;
        PRUEFE(!c.warteAufBild(&b2, 100));   // ausstehend
        PRUEFE(c.istVerbunden());
        KameraBild falsch = a;
        falsch.bildnummer = 99;
        PRUEFE(!c.gibZurueck(&falsch, -1));
        PRUEFE(c.istVerbunden());
        PRUEFE(c.gibZurueck(&a, -1));
        PRUEFE(c.warteAufBild(&b2, 1000));
        PRUEFE(b2.bildnummer == 2 && b2.puffer_platz == 1);
        t.join();
        PRUEFE(fences == 0);
        delete s;
    });

    test("Rueckgabe scheitert (Server weg) → getrennt beim naechsten Warten", [&] {
        KameraClient c;
        std::thread t;
        Server* s;
        bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
            auto b = begruessung(true);
            auto fds = puffer(b);
            sv.senden(&b, sizeof(b), fds);
            schliessen(fds);
            auto n = bild(7, 0, 1, 64, 4);
            sv.senden(&n, sizeof(n));
            usleep(100000);
            close(sv.kunde);
            sv.kunde = -1;
        }, &t, &s);
        PRUEFE(ok);
        KameraBild a;
        PRUEFE(c.warteAufBild(&a, 1000));
        t.join();
        usleep(50000);
        bool zurueck = c.gibZurueck(&a, -1);
        // Je nach Puffer des Kernels kann der erste Sendeversuch noch gelingen; danach meldet Warten die Trennung.
        KameraBild b;
        if (zurueck) PRUEFE(!c.warteAufBild(&b, 300));
        PRUEFE(!c.istVerbunden());
        delete s;
    });

    test("Neu verbinden raeumt die alte Verbindung auf", [&] {
        KameraClient c;
        for (int runde = 0; runde < 3; runde++) {
            std::thread t;
            Server* s;
            bool ok = verbindeMit(c, neuerName(), ich, [&](Server& sv) {
                auto b = begruessung(true);
                auto fds = puffer(b);
                sv.senden(&b, sizeof(b), fds);
                schliessen(fds);
                usleep(100000);
            }, &t, &s);
            PRUEFE(ok);
            t.join();
            delete s;
        }
        c.trennen();
    });

    std::printf("%s (%d Fehler)\n", g_fehler == 0 ? "ALLE GRUEN" : "ROT", g_fehler);
    return g_fehler == 0 ? 0 : 1;
}
