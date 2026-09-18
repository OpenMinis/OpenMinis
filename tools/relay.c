#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <arpa/inet.h>

static char g_name[128];

static void *pump(void *arg) {
    int from = ((int *)arg)[0], to = ((int *)arg)[1];
    char buf[8192];
    ssize_t n;
    while ((n = read(from, buf, sizeof buf)) > 0) {
        if (write(to, buf, n) <= 0) break;
    }
    shutdown(to, SHUT_WR);
    close(from);
    free(arg);
    return NULL;
}

int main(int argc, char **argv) {
    int port = argc > 1 ? atoi(argv[1]) : 9222;
    snprintf(g_name, sizeof g_name, "%s", argc > 2 ? argv[2] : "chrome_devtools_remote");

    int ls = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(ls, (struct sockaddr *)&a, sizeof a) || listen(ls, 8)) { perror("bind/listen"); return 1; }
    fprintf(stderr, "relay 127.0.0.1:%d -> @%s\n", port, g_name);
    fflush(stderr);

    for (;;) {
        int c = accept(ls, NULL, NULL);
        if (c < 0) continue;
        int u = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un ua;
        memset(&ua, 0, sizeof ua);
        ua.sun_family = AF_UNIX;
        ua.sun_path[0] = 0;
        strncpy(ua.sun_path + 1, g_name, sizeof(ua.sun_path) - 2);
        socklen_t ul = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(g_name);
        if (connect(u, (struct sockaddr *)&ua, ul)) {
            perror("connect");
            close(c);
            close(u);
            continue;
        }
        int *f1 = malloc(2 * sizeof(int)), *f2 = malloc(2 * sizeof(int));
        f1[0] = c; f1[1] = u;
        f2[0] = u; f2[1] = c;
        pthread_t t;
        pthread_create(&t, NULL, pump, f1); pthread_detach(t);
        pthread_create(&t, NULL, pump, f2); pthread_detach(t);
    }
}
