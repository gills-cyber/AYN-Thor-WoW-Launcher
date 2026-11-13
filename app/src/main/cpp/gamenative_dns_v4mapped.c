/*
 * Android's getaddrinfo() rejects AI_V4MAPPED, while Windows applications
 * commonly use AF_INET6 + AI_V4MAPPED with a dual-stack socket.  Provide the
 * Windows fallback for IPv4-only hosts by returning IPv4-mapped IPv6 results.
 *
 * Android also reports the machine hostname as "localhost", so applications
 * that discover their own address via getaddrinfo(gethostname()) - notably
 * Epic Online Services' P2P stack - resolve 127.0.0.1 and bind their gameplay
 * sockets to loopback, which breaks online multiplayer.  gethostname() is
 * interposed below to report the device's routable address instead.
 *
 * This shim is preloaded for every bionic launch.
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#define DNS_LOG(format, ...) \
    dprintf(STDERR_FILENO, "[GameNativeDNS] " format "\n", ##__VA_ARGS__)

typedef uint64_t android_net_handle_t;

static int (*android_res_nquery_fn)(android_net_handle_t, const char *, int, int,
                                    uint32_t);
static int (*android_res_nresult_fn)(int, int *, uint8_t *, size_t);
static int (*android_getaddrinfofornetwork_fn)(android_net_handle_t,
                                               const char *, const char *,
                                               const struct addrinfo *,
                                               struct addrinfo **);
static pthread_once_t android_resolver_once = PTHREAD_ONCE_INIT;

static void resolve_android_resolver(void)
{
    void *handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);

    if (!handle)
    {
        DNS_LOG("dlopen(libandroid.so) failed: %s", dlerror());
        return;
    }

    android_res_nquery_fn = dlsym(handle, "android_res_nquery");
    android_res_nresult_fn = dlsym(handle, "android_res_nresult");
    android_getaddrinfofornetwork_fn =
        dlsym(handle, "android_getaddrinfofornetwork");
    if (!android_res_nquery_fn || !android_res_nresult_fn)
        DNS_LOG("Android async resolver symbols are unavailable");
}

static int rcode_to_h_errno(int rcode)
{
    switch (rcode)
    {
        case 2:  return TRY_AGAIN;       /* SERVFAIL */
        case 3:  return HOST_NOT_FOUND;  /* NXDOMAIN */
        case 0:  return NO_DATA;
        default: return NO_RECOVERY;
    }
}

/*
 * Wine's dnsapi unixlib calls res_query().  Bionic exposes that legacy API,
 * but it does not reliably carry the Android application network context.
 * Route the query through Android's public asynchronous resolver instead.
 */
int res_query(const char *name, int dns_class, int type,
              unsigned char *answer, int answer_length)
{
    struct pollfd query_poll;
    int fd, poll_result, rcode = 0, result;

    pthread_once(&android_resolver_once, resolve_android_resolver);
    if (!android_res_nquery_fn || !android_res_nresult_fn)
    {
        h_errno = NO_RECOVERY;
        errno = ENOSYS;
        return -1;
    }

    fd = android_res_nquery_fn(0, name, dns_class, type, 0);
    if (fd < 0)
    {
        h_errno = TRY_AGAIN;
        errno = -fd;
        DNS_LOG("android_res_nquery(%s, type=%d) failed: %d", name, type, fd);
        return -1;
    }

    query_poll.fd = fd;
    query_poll.events = POLLIN;
    query_poll.revents = 0;
    do
    {
        poll_result = poll(&query_poll, 1, 30000);
    } while (poll_result < 0 && errno == EINTR);

    if (poll_result <= 0)
    {
        int saved_errno = poll_result == 0 ? ETIMEDOUT : errno;
        close(fd);
        h_errno = TRY_AGAIN;
        errno = saved_errno;
        DNS_LOG("DNS query for %s timed out or poll failed: %d", name,
                saved_errno);
        return -1;
    }

    result = android_res_nresult_fn(fd, &rcode, answer, (size_t)answer_length);
    if (result < 0 || rcode)
    {
        h_errno = result < 0 ? TRY_AGAIN : rcode_to_h_errno(rcode);
        if (result < 0) errno = -result;
        DNS_LOG("DNS result for %s failed: result=%d rcode=%d", name, result,
                rcode);
        return -1;
    }

    DNS_LOG("resolved %s type=%d (%d-byte reply)", name, type, result);
    return result;
}

struct mapped_result
{
    struct addrinfo *head;
    struct mapped_result *next;
};

static int (*next_getaddrinfo)(const char *, const char *, const struct addrinfo *,
                               struct addrinfo **);
static void (*next_freeaddrinfo)(struct addrinfo *);
static int (*next_gethostname)(char *, size_t);
static pthread_once_t resolver_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t mapped_results_lock = PTHREAD_MUTEX_INITIALIZER;
static struct mapped_result *mapped_results;

static void resolve_next_symbols(void)
{
    next_getaddrinfo = dlsym(RTLD_NEXT, "getaddrinfo");
    next_freeaddrinfo = dlsym(RTLD_NEXT, "freeaddrinfo");
    next_gethostname = dlsym(RTLD_NEXT, "gethostname");
}

static int network_getaddrinfo(const char *node, const char *service,
                               const struct addrinfo *hints,
                               struct addrinfo **result)
{
    int ret;

    pthread_once(&android_resolver_once, resolve_android_resolver);
    if (android_getaddrinfofornetwork_fn)
        ret = android_getaddrinfofornetwork_fn(0, node, service, hints, result);
    else
        ret = next_getaddrinfo(node, service, hints, result);

    if (node && (!hints || !(hints->ai_flags & AI_NUMERICHOST)))
        DNS_LOG("getaddrinfo(%s, family=%d, flags=0x%x) -> %d", node,
                hints ? hints->ai_family : AF_UNSPEC,
                hints ? hints->ai_flags : 0, ret);
    return ret;
}

static void free_mapped_list(struct addrinfo *head)
{
    while (head)
    {
        struct addrinfo *next = head->ai_next;
        free(head->ai_canonname);
        free(head->ai_addr);
        free(head);
        head = next;
    }
}

static int track_mapped_list(struct addrinfo *head)
{
    struct mapped_result *entry = malloc(sizeof(*entry));
    if (!entry) return EAI_MEMORY;

    entry->head = head;
    pthread_mutex_lock(&mapped_results_lock);
    entry->next = mapped_results;
    mapped_results = entry;
    pthread_mutex_unlock(&mapped_results_lock);
    return 0;
}

static int map_ipv4_results(const struct addrinfo *ipv4, struct addrinfo **result)
{
    struct addrinfo *head = NULL;
    struct addrinfo **tail = &head;

    for (; ipv4; ipv4 = ipv4->ai_next)
    {
        const struct sockaddr_in *source;
        struct sockaddr_in6 *address;
        struct addrinfo *entry;

        if (ipv4->ai_family != AF_INET ||
            ipv4->ai_addrlen < (socklen_t)sizeof(*source))
            continue;

        entry = calloc(1, sizeof(*entry));
        address = calloc(1, sizeof(*address));
        if (!entry || !address)
        {
            free(entry);
            free(address);
            free_mapped_list(head);
            return EAI_MEMORY;
        }

        source = (const struct sockaddr_in *)ipv4->ai_addr;
        address->sin6_family = AF_INET6;
        address->sin6_port = source->sin_port;
        address->sin6_addr.s6_addr[10] = 0xff;
        address->sin6_addr.s6_addr[11] = 0xff;
        memcpy(&address->sin6_addr.s6_addr[12], &source->sin_addr,
               sizeof(source->sin_addr));

        entry->ai_flags = ipv4->ai_flags;
        entry->ai_family = AF_INET6;
        entry->ai_socktype = ipv4->ai_socktype;
        entry->ai_protocol = ipv4->ai_protocol;
        entry->ai_addrlen = sizeof(*address);
        entry->ai_addr = (struct sockaddr *)address;
        if (ipv4->ai_canonname)
        {
            entry->ai_canonname = strdup(ipv4->ai_canonname);
            if (!entry->ai_canonname)
            {
                free_mapped_list(entry);
                free_mapped_list(head);
                return EAI_MEMORY;
            }
        }

        *tail = entry;
        tail = &entry->ai_next;
    }

    if (!head) return EAI_NONAME;
    if (track_mapped_list(head))
    {
        free_mapped_list(head);
        return EAI_MEMORY;
    }

    *result = head;
    return 0;
}

/*
 * The device's routable IPv4 address as text.  Connecting a UDP socket reveals
 * the source address the kernel would use, without the interface enumeration
 * the app sandbox filters.  connect() on a datagram socket sends no packets.
 * Returns 0 on success.
 */
static int routable_ipv4(char *out, size_t len)
{
    struct sockaddr_in probe;
    socklen_t probe_len = sizeof(probe);
    char address[INET_ADDRSTRLEN];
    int fd;

    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0)
        return -1;

    memset(&probe, 0, sizeof(probe));
    probe.sin_family = AF_INET;
    probe.sin_port = htons(53);
    probe.sin_addr.s_addr = htonl(0x08080808); /* any routable destination */
    if (connect(fd, (const struct sockaddr *)&probe, sizeof(probe)) != 0 ||
        getsockname(fd, (struct sockaddr *)&probe, &probe_len) != 0)
    {
        close(fd);
        return -1;
    }
    close(fd);

    if (probe.sin_addr.s_addr == 0 ||
        (ntohl(probe.sin_addr.s_addr) >> 24) == 127)
        return -1;
    if (!inet_ntop(AF_INET, &probe.sin_addr, address, sizeof(address)))
        return -1;
    if (strlen(address) + 1 > len)
        return -1;
    memcpy(out, address, strlen(address) + 1);
    return 0;
}

/* The stable per-install computer name supplied by the app, or NULL. */
static const char *fixed_hostname(void)
{
    const char *fixed = getenv("GAMENATIVE_HOSTNAME");
    return (fixed && fixed[0] != '\0') ? fixed : NULL;
}

/*
 * The fixed computer name is not in any DNS.  A lookup of it must still give
 * the device's own address, exactly as when gethostname() reported the IP
 * itself.  Returns the name to really look up: an address in buf when node is
 * our own name, node otherwise.
 */
static const char *own_name_to_address(const char *node, char *buf, size_t len)
{
    const char *fixed = fixed_hostname();

    if (!node || !fixed || strcasecmp(node, fixed) != 0)
        return node;
    if (routable_ipv4(buf, len) != 0)
        snprintf(buf, len, "127.0.0.1");
    return buf;
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **result)
{
    struct addrinfo fallback_hints;
    struct addrinfo *ipv4 = NULL;
    char own_address[INET_ADDRSTRLEN];
    int ret;

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_getaddrinfo)
    {
        errno = ENOSYS;
        return EAI_SYSTEM;
    }

    node = own_name_to_address(node, own_address, sizeof(own_address));

    if (hints && hints->ai_family != AF_INET6 &&
        (hints->ai_flags & (AI_V4MAPPED | AI_ALL)))
    {
        /*
         * WinSock accepts AI_V4MAPPED with AF_UNSPEC and simply ignores it.
         * Bionic instead rejects the whole request with EAI_BADFLAGS.
         */
        fallback_hints = *hints;
        fallback_hints.ai_flags &= ~(AI_V4MAPPED | AI_ALL);
        return network_getaddrinfo(node, service, &fallback_hints, result);
    }

    if (!hints || hints->ai_family != AF_INET6 ||
        !(hints->ai_flags & AI_V4MAPPED))
        return network_getaddrinfo(node, service, hints, result);

    fallback_hints = *hints;
    fallback_hints.ai_flags &= ~(AI_V4MAPPED | AI_ALL);

    /* Prefer a genuine IPv6 answer, as Windows does without AI_ALL. */
    ret = network_getaddrinfo(node, service, &fallback_hints, result);
    if (!ret) return 0;

    fallback_hints.ai_family = AF_INET;
    ret = network_getaddrinfo(node, service, &fallback_hints, &ipv4);
    if (ret) return ret;

    ret = map_ipv4_results(ipv4, result);
    next_freeaddrinfo(ipv4);
    return ret;
}

void freeaddrinfo(struct addrinfo *result)
{
    struct mapped_result **cursor;
    struct mapped_result *matched = NULL;

    pthread_once(&resolver_once, resolve_next_symbols);

    pthread_mutex_lock(&mapped_results_lock);
    for (cursor = &mapped_results; *cursor; cursor = &(*cursor)->next)
    {
        if ((*cursor)->head == result)
        {
            matched = *cursor;
            *cursor = matched->next;
            break;
        }
    }
    pthread_mutex_unlock(&mapped_results_lock);

    if (matched)
    {
        free_mapped_list(matched->head);
        free(matched);
    }
    else if (next_freeaddrinfo)
    {
        next_freeaddrinfo(result);
    }
}

/*
 * Android's hostname is "localhost", which resolves to 127.0.0.1.  When that
 * is the answer, report the device's routable address instead: connecting a
 * UDP socket reveals the source address the kernel would use, without the
 * interface enumeration the app sandbox filters.  connect() on a datagram
 * socket sends no packets.  On any failure the real hostname is kept.
 */
int gethostname(char *name, size_t len)
{
    char address[INET_ADDRSTRLEN];
    const char *fixed;
    size_t fixed_len;
    int ret;

    /*
     * A stable per-install computer name supplied by the app.  Without it the
     * Windows computer name followed the device IP and changed per network,
     * which Battle.net rejects with error BLZ51914400.  Lookups of this name
     * are answered with the device address, see own_name_to_address().
     */
    fixed = fixed_hostname();
    if (fixed && name)
    {
        fixed_len = strlen(fixed);
        if (fixed_len + 1 <= len)
        {
            memcpy(name, fixed, fixed_len + 1);
            return 0;
        }
    }

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_gethostname)
    {
        errno = ENOSYS;
        return -1;
    }

    ret = next_gethostname(name, len);
    if (ret != 0)
        return ret;
    if (strcmp(name, "localhost") != 0 && name[0] != '\0')
        return 0;

    if (routable_ipv4(address, sizeof(address)) != 0)
        return 0;
    if (strlen(address) + 1 > len)
        return 0;

    memcpy(name, address, strlen(address) + 1);
    DNS_LOG("gethostname -> %s (hostname was localhost)", address);
    return 0;
}

/* The older lookup calls get the same answer for the fixed computer name. */
struct hostent *gethostbyname(const char *name)
{
    static struct hostent *(*next)(const char *);
    char own_address[INET_ADDRSTRLEN];

    if (!next)
        next = dlsym(RTLD_NEXT, "gethostbyname");
    if (!next)
    {
        h_errno = NO_RECOVERY;
        return NULL;
    }
    return next(own_name_to_address(name, own_address, sizeof(own_address)));
}

int gethostbyname_r(const char *name, struct hostent *ret, char *buf,
                    size_t buflen, struct hostent **result, int *h_errnop)
{
    static int (*next)(const char *, struct hostent *, char *, size_t,
                       struct hostent **, int *);
    char own_address[INET_ADDRSTRLEN];

    if (!next)
        next = dlsym(RTLD_NEXT, "gethostbyname_r");
    if (!next)
    {
        if (result) *result = NULL;
        if (h_errnop) *h_errnop = NO_RECOVERY;
        return ENOSYS;
    }
    return next(own_name_to_address(name, own_address, sizeof(own_address)),
                ret, buf, buflen, result, h_errnop);
}
