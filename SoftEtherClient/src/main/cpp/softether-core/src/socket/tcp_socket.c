#include "softether_socket.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <poll.h>
#include <sys/time.h>
#include <netinet/tcp.h>
#include <android/log.h>

#define TAG "SoftEtherSocket"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

softether_socket_t* socket_create(int type) {
    softether_socket_t* sock = (softether_socket_t*)calloc(1, sizeof(softether_socket_t));
    if (sock == NULL) {
        LOGE("Failed to allocate socket structure");
        return NULL;
    }
    
    int socket_type = (type == SOCKET_TYPE_UDP) ? SOCK_DGRAM : SOCK_STREAM;
    sock->fd = socket(AF_INET, socket_type, 0);
    if (sock->fd < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        free(sock);
        return NULL;
    }
    
    sock->type = type;
    sock->family = AF_INET;
    sock->connected = 0;
    sock->timeout_ms = SOCKET_TIMEOUT_MS;
    
    // Set non-blocking mode initially
    int flags = fcntl(sock->fd, F_GETFL, 0);
    fcntl(sock->fd, F_SETFL, flags | O_NONBLOCK);
    
    LOGD("Socket created: fd=%d, type=%d", sock->fd, type);
    return sock;
}

void socket_destroy(softether_socket_t* sock) {
    if (sock == NULL) {
        return;
    }
    
    if (sock->fd >= 0) {
        close(sock->fd);
        LOGD("Socket closed: fd=%d", sock->fd);
    }
    
    free(sock);
}

int resolve_hostname(const char* hostname, char* ip_buffer, size_t buffer_size) {
    if (hostname == NULL || ip_buffer == NULL || buffer_size == 0) {
        return -1;
    }

    // Literal IPs (v4 or v6) pass through unchanged
    struct in_addr in4;
    struct in6_addr in6;
    if (inet_pton(AF_INET, hostname, &in4) == 1 ||
        inet_pton(AF_INET6, hostname, &in6) == 1) {
        strncpy(ip_buffer, hostname, buffer_size - 1);
        ip_buffer[buffer_size - 1] = '\0';
        return 0;
    }

    struct addrinfo hints;
    struct addrinfo* res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;      // dual-stack
    hints.ai_socktype = SOCK_STREAM;

    int rc = getaddrinfo(hostname, NULL, &hints, &res);
    if (rc != 0 || res == NULL) {
        LOGE("Failed to resolve hostname: %s (%s)", hostname, gai_strerror(rc));
        return -1;
    }

    // Use the first address returned by resolution (no family preference).
    struct addrinfo* best = res;

    const void* src = NULL;
    if (best == NULL) {
        freeaddrinfo(res);
        LOGE("No addresses found for hostname: %s", hostname);
        return -1;
    } else if (best->ai_family == AF_INET) {
        src = &((struct sockaddr_in*)best->ai_addr)->sin_addr;
    } else if (best->ai_family == AF_INET6) {
        src = &((struct sockaddr_in6*)best->ai_addr)->sin6_addr;
    }

    if (src == NULL ||
        inet_ntop(best->ai_family, src, ip_buffer, buffer_size) == NULL) {
        freeaddrinfo(res);
        LOGE("Failed to format address for hostname: %s", hostname);
        return -1;
    }

    freeaddrinfo(res);
    LOGD("Resolved %s to %s", hostname, ip_buffer);
    return 0;
}

// Resolve a hostname (or literal IP) to an address of a specific family.
// Returns 0 and fills ip_buffer on success, -1 if the family is unavailable.
int resolve_hostname_family(const char* hostname, int family, char* ip_buffer, size_t buffer_size) {
    if (hostname == NULL || ip_buffer == NULL || buffer_size == 0 ||
        (family != AF_INET && family != AF_INET6)) {
        return -1;
    }

    // Literal IPs pass through unchanged, but only if they match the requested family.
    struct in_addr in4;
    struct in6_addr in6;
    if (family == AF_INET && inet_pton(AF_INET, hostname, &in4) == 1) {
        strncpy(ip_buffer, hostname, buffer_size - 1);
        ip_buffer[buffer_size - 1] = '\0';
        return 0;
    }
    if (family == AF_INET6 && inet_pton(AF_INET6, hostname, &in6) == 1) {
        strncpy(ip_buffer, hostname, buffer_size - 1);
        ip_buffer[buffer_size - 1] = '\0';
        return 0;
    }

    struct addrinfo hints;
    struct addrinfo* res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = family;
    hints.ai_socktype = SOCK_STREAM;

    int rc = getaddrinfo(hostname, NULL, &hints, &res);
    if (rc != 0 || res == NULL) {
        LOGD("No %s address for %s (%s)",
             family == AF_INET ? "IPv4" : "IPv6", hostname, gai_strerror(rc));
        return -1;
    }

    struct addrinfo* best = res;  // first matching address
    const void* src = NULL;
    if (best->ai_family == AF_INET) {
        src = &((struct sockaddr_in*)best->ai_addr)->sin_addr;
    } else if (best->ai_family == AF_INET6) {
        src = &((struct sockaddr_in6*)best->ai_addr)->sin6_addr;
    }

    int result = -1;
    if (src != NULL && inet_ntop(best->ai_family, src, ip_buffer, buffer_size) != NULL) {
        LOGD("Resolved %s to %s (%s)", hostname, ip_buffer,
             best->ai_family == AF_INET ? "IPv4" : "IPv6");
        result = 0;
    }
    freeaddrinfo(res);
    return result;
}

// Close and re-create sock->fd for the given address family, re-applying
// non-blocking mode and socket timeouts. No-op if the family already matches.
static int socket_recreate_for_family(softether_socket_t* sock, int family) {
    if (sock == NULL || (family != AF_INET && family != AF_INET6)) {
        return -1;
    }
    if (sock->fd >= 0 && sock->family == family) {
        return 0;
    }

    if (sock->fd >= 0) {
        close(sock->fd);
        sock->fd = -1;
    }

    int socket_type = (sock->type == SOCKET_TYPE_UDP) ? SOCK_DGRAM : SOCK_STREAM;
    sock->fd = socket(family, socket_type, 0);
    if (sock->fd < 0) {
        sock->family = AF_UNSPEC;
        LOGE("Failed to create socket family=%d: %s", family, strerror(errno));
        return -1;
    }
    sock->family = family;

    int flags = fcntl(sock->fd, F_GETFL, 0);
    fcntl(sock->fd, F_SETFL, flags | O_NONBLOCK);

    if (sock->timeout_ms > 0) {
        struct timeval tv;
        tv.tv_sec = sock->timeout_ms / 1000;
        tv.tv_usec = (sock->timeout_ms % 1000) * 1000;
        setsockopt(sock->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock->fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    }

    LOGD("Socket recreated for family=%d: fd=%d", family, sock->fd);
    return 0;
}

// Attempt a non-blocking connect to a single resolved address.
// On success, sock->addr holds the connected peer address and 0 is returned.
static int try_connect_addr(softether_socket_t* sock, struct addrinfo* ai,
                            int timeout_ms, char* log_ip, size_t log_ip_size) {
    if (socket_recreate_for_family(sock, ai->ai_family) != 0) {
        return -1;
    }

    memset(&sock->addr, 0, sizeof(sock->addr));
    memcpy(&sock->addr, ai->ai_addr, ai->ai_addrlen);
    sock->addr_len = ai->ai_addrlen;

    if (log_ip && log_ip_size > 0) {
        const void* src = NULL;
        if (ai->ai_family == AF_INET) {
            src = &((struct sockaddr_in*)ai->ai_addr)->sin_addr;
        } else if (ai->ai_family == AF_INET6) {
            src = &((struct sockaddr_in6*)ai->ai_addr)->sin6_addr;
        }
        if (src != NULL) {
            inet_ntop(ai->ai_family, src, log_ip, log_ip_size);
        }
    }

    int result = connect(sock->fd, (struct sockaddr*)&sock->addr, sock->addr_len);

    if (result < 0 && errno == EINPROGRESS) {
        struct pollfd pfd;
        pfd.fd = sock->fd;
        pfd.events = POLLOUT;

        result = poll(&pfd, 1, timeout_ms);

        if (result < 0) {
            LOGE("Poll failed: %s", strerror(errno));
            return -1;
        } else if (result == 0) {
            LOGE("Connection timeout");
            return -1;
        }

        // Check if connection succeeded
        int so_error;
        socklen_t len = sizeof(so_error);
        getsockopt(sock->fd, SOL_SOCKET, SO_ERROR, &so_error, &len);

        if (so_error != 0) {
            LOGE("Connection failed: %s", strerror(so_error));
            return -1;
        }
    } else if (result < 0) {
        LOGE("Connect failed: %s", strerror(errno));
        return -1;
    }

    return 0;
}

int socket_connect_timeout(softether_socket_t* sock, const char* host, int port, int timeout_ms) {
    if (sock == NULL || host == NULL) {
        LOGE("Invalid socket");
        return -1;
    }

    struct addrinfo hints;
    struct addrinfo* res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;      // dual-stack
    hints.ai_socktype = SOCK_STREAM;

    // Skip DNS for literal IPs (v4 or v6)
    struct in_addr in4;
    struct in6_addr in6;
    if (inet_pton(AF_INET, host, &in4) == 1) {
        hints.ai_family = AF_INET;
    } else if (inet_pton(AF_INET6, host, &in6) == 1) {
        hints.ai_family = AF_INET6;
    }

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    int rc = getaddrinfo(host, port_str, &hints, &res);
    if (rc != 0 || res == NULL) {
        LOGE("Failed to resolve %s:%d (%s)", host, port, gai_strerror(rc));
        return -1;
    }

    char last_ip[INET6_ADDRSTRLEN] = "";
    struct addrinfo* ai;
    int attempts = 0;

    // Pass 1: the family of the first resolved address (natural DNS order).
    // Pass 2: the remaining IP version.
    int first_family = (res->ai_family == AF_INET) ? AF_INET : AF_INET6;
    int second_family = (first_family == AF_INET) ? AF_INET6 : AF_INET;

    for (ai = res; ai != NULL; ai = ai->ai_next) {
        if (ai->ai_family != first_family) continue;
        attempts++;
        if (try_connect_addr(sock, ai, timeout_ms, last_ip, sizeof(last_ip)) == 0) {
            freeaddrinfo(res);
            // Set back to blocking mode
            int flags = fcntl(sock->fd, F_GETFL, 0);
            fcntl(sock->fd, F_SETFL, flags & ~O_NONBLOCK);
            sock->connected = 1;
            sock->timeout_ms = timeout_ms;
            LOGD("Connected to %s:%d", last_ip[0] ? last_ip : host, port);
            return 0;
        }
    }

    // Pass 2: remaining IP version
    for (ai = res; ai != NULL; ai = ai->ai_next) {
        if (ai->ai_family != second_family) continue;
        attempts++;
        if (try_connect_addr(sock, ai, timeout_ms, last_ip, sizeof(last_ip)) == 0) {
            freeaddrinfo(res);
            // Set back to blocking mode
            int flags = fcntl(sock->fd, F_GETFL, 0);
            fcntl(sock->fd, F_SETFL, flags & ~O_NONBLOCK);
            sock->connected = 1;
            sock->timeout_ms = timeout_ms;
            LOGD("Connected to %s:%d", last_ip[0] ? last_ip : host, port);
            return 0;
        }
    }

    freeaddrinfo(res);
    LOGE("Connect failed for %s:%d (%d attempts)", host, port, attempts);
    return -1;
}

int socket_connect(softether_socket_t* sock, const char* host, int port) {
    return socket_connect_timeout(sock, host, port, SOCKET_TIMEOUT_MS);
}

int socket_disconnect(softether_socket_t* sock) {
    if (sock == NULL) {
        return -1;
    }
    
    if (sock->fd >= 0) {
        shutdown(sock->fd, SHUT_RDWR);
        close(sock->fd);
        sock->fd = -1;
    }
    
    sock->connected = 0;
    LOGD("Socket disconnected");
    return 0;
}

int socket_send_all(softether_socket_t* sock, const uint8_t* data, size_t len, int timeout_ms) {
    if (sock == NULL || !sock->connected) {
        LOGE("Socket not connected");
        return -1;
    }
    
    size_t total_sent = 0;
    
    while (total_sent < len) {
        struct pollfd pfd;
        pfd.fd = sock->fd;
        pfd.events = POLLOUT;

        int result = poll(&pfd, 1, timeout_ms);
        if (result < 0) {
            LOGE("Poll failed: %s", strerror(errno));
            return -1;
        } else if (result == 0) {
            LOGE("Send timeout");
            return -1;
        }
        
        ssize_t sent = send(sock->fd, data + total_sent, len - total_sent, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGE("Send failed: %s", strerror(errno));
            return -1;
        }
        
        total_sent += sent;
    }
    
    return (int)total_sent;
}

int socket_send(softether_socket_t* sock, const uint8_t* data, size_t len) {
    return socket_send_all(sock, data, len, sock->timeout_ms);
}

int socket_recv_all(softether_socket_t* sock, uint8_t* buffer, size_t len, int timeout_ms) {
    if (sock == NULL || !sock->connected) {
        LOGE("Socket not connected");
        return -1;
    }
    
    size_t total_received = 0;
    
    while (total_received < len) {
        struct pollfd pfd;
        pfd.fd = sock->fd;
        pfd.events = POLLIN;

        int result = poll(&pfd, 1, timeout_ms);
        if (result < 0) {
            LOGE("Poll failed: %s", strerror(errno));
            return -1;
        } else if (result == 0) {
            LOGE("Receive timeout");
            return -1;
        }
        
        ssize_t received = recv(sock->fd, buffer + total_received, len - total_received, 0);
        if (received < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGE("Receive failed: %s", strerror(errno));
            return -1;
        } else if (received == 0) {
            LOGE("Connection closed by peer");
            return -1;
        }
        
        total_received += received;
    }
    
    return (int)total_received;
}

int socket_recv(softether_socket_t* sock, uint8_t* buffer, size_t max_len) {
    return socket_recv_all(sock, buffer, max_len, sock->timeout_ms);
}

// Apply SO_RCVTIMEO / SO_SNDTIMEO to a raw socket fd.
//
// NATIVE-FIX-HANDSHAKE-TIMEOUT (P1): socket_connect_timeout() deliberately
// switches a freshly connected fd back to BLOCKING mode. On a blocking socket
// the ONLY thing that can interrupt a stalled recv()/send() is
// SO_RCVTIMEO / SO_SNDTIMEO; with the kernel default (0 = no timeout) a
// blocking recv() waits forever. That is the single root cause identified by
// AUDIT_AUTOCONNECT_LOGIC.md §1.2 and §5: the TLS handshake runs on such a
// socket, so SSL_do_handshake() could block indefinitely while holding
// g_openssl_lock + g_tls_use_lock.
//
// This is the single implementation of "give this fd the connect timeout".
// socket_set_timeout() (which takes a softether_socket_t*) delegates to it, and
// the connect paths in softether_protocol.c call it directly because they hold
// only the raw fd — the socket struct has already been destroyed by then.
//
// With a timeout applied, a stalled recv()/send() returns EAGAIN, which OpenSSL
// surfaces as SSL_ERROR_WANT_READ / WANT_WRITE. That is exactly the condition
// the existing retry loop in aes_wrapper.c (ssl_connect, max_attempts) already
// handles, so this helper makes that loop effective without modifying it.
//
// timeout_ms <= 0 is a deliberate no-op: the caller asked for no bound, and the
// kernel default is left in place.
//
// @return 0 on success (including the no-op case), -1 if fd < 0 or either
//   setsockopt failed.
int socket_apply_timeout(int fd, int timeout_ms) {
    if (fd < 0) {
        return -1;
    }
    if (timeout_ms <= 0) {
        return 0;
    }

    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;

    int rc_recv = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int rc_send = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (rc_recv != 0 || rc_send != 0) {
        LOGE("Failed to apply socket timeout %d ms to fd=%d: %s",
             timeout_ms, fd, strerror(errno));
        return -1;
    }

    LOGD("Applied socket timeout %d ms to fd=%d (SO_RCVTIMEO+SO_SNDTIMEO)",
         timeout_ms, fd);
    return 0;
}

int socket_set_timeout(softether_socket_t* sock, int timeout_ms) {
    if (sock == NULL) {
        return -1;
    }
    
    sock->timeout_ms = timeout_ms;
    
    // Single implementation lives in socket_apply_timeout().
    return socket_apply_timeout(sock->fd, timeout_ms);
}

int socket_set_nodelay(softether_socket_t* sock, int enable) {
    if (sock == NULL) {
        return -1;
    }
    
    return setsockopt(sock->fd, IPPROTO_TCP, TCP_NODELAY, &enable, sizeof(enable));
}

int socket_set_keepalive(softether_socket_t* sock, int enable) {
    if (sock == NULL) {
        return -1;
    }
    
    return setsockopt(sock->fd, SOL_SOCKET, SO_KEEPALIVE, &enable, sizeof(enable));
}

int socket_is_connected(softether_socket_t* sock) {
    if (sock == NULL) {
        return 0;
    }
    return sock->connected;
}

int socket_get_error(softether_socket_t* sock) {
    if (sock == NULL || sock->fd < 0) {
        return EBADF;
    }
    
    int so_error;
    socklen_t len = sizeof(so_error);
    getsockopt(sock->fd, SOL_SOCKET, SO_ERROR, &so_error, &len);
    return so_error;
}

const char* socket_error_string(int error) {
    return strerror(error);
}
