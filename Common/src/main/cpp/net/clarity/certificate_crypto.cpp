// SPDX-License-Identifier: GPL-3.0-or-later
#include "certificate.hpp"
#include <cstddef>
#include <cstring>
#include <dlfcn.h>
#include <openssl/ec.h>
#include <openssl/err.h>
#ifdef __ANDROID__
extern void *opencrypto();
#endif
namespace clarity {
namespace {
void *cryptoHandle() {
#ifdef __ANDROID__
    auto *handle = opencrypto();
#else
    auto *handle = dlopen("libcrypto.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle)
        handle = dlopen("libcrypto.so.3", RTLD_NOW | RTLD_LOCAL);
#endif
    if (!handle)
        throw Error("Client certificate crypto library unavailable");
    return handle; // Kept alive like ContextHTTPS's process-lifetime library.
}
struct Crypto {
    void *handle = cryptoHandle();
    template <class T> T symbol(const char *name) {
        auto p = reinterpret_cast<T>(dlsym(handle, name));
        if (!p)
            throw Error(std::string("Client certificate crypto function unavailable: ") + name);
        return p;
    }
    bool isBoringSSL() {
        auto version = reinterpret_cast<const char *(*)(int)>(dlsym(handle, "OpenSSL_version"));
        if (!version)
            version = symbol<const char *(*)(int)>("SSLeay_version");
        const char *name = version(0 /* OPENSSL_VERSION */);
        return name && std::string_view(name).starts_with("BoringSSL");
    }
    const bool boringSSL = isBoringSSL();
    // The bundled OpenSSL headers declare int len; Android's BoringSSL uses
    // ossl_ssize_t (ptrdiff_t). In particular, a 32-bit -1 can arrive as
    // 0x00000000ffffffff on ARM64. Resolve the actual ABI and pass positive
    // lengths, never the strlen sentinel. loc/set remain int in both APIs.
    template <class Length>
    using AddName = int (*)(X509_NAME *, const char *, int, const unsigned char *, Length, int, int);
    void *addNameSymbol = symbol<void *>("X509_NAME_add_entry_by_txt");
    int addName(X509_NAME *name, const char *field, const char *value) {
        const auto *data = reinterpret_cast<const unsigned char *>(value);
        const auto size = std::strlen(value); // Only the fixed, short G7 subject literals.
        if (boringSSL)
            return reinterpret_cast<AddName<std::ptrdiff_t>>(addNameSymbol)(
                name, field, MBSTRING_ASC, data, static_cast<std::ptrdiff_t>(size), -1, 0);
        return reinterpret_cast<AddName<int>>(addNameSymbol)(
            name, field, MBSTRING_ASC, data, static_cast<int>(size), -1, 0);
    }
    void *peekErrorSymbol = symbol<void *>("ERR_peek_last_error");
    void *reasonSymbol = symbol<void *>("ERR_reason_error_string");
    // BoringSSL's error code is uint32_t; OpenSSL's is unsigned long.
    template <class Code> const char *errorReason() {
        const auto code = reinterpret_cast<Code (*)()>(peekErrorSymbol)();
        return code ? reinterpret_cast<const char *(*)(Code)>(reasonSymbol)(code) : nullptr;
    }
    const char *errorReason() {
        return boringSSL ? errorReason<uint32_t>() : errorReason<unsigned long>();
    }
#define CERT_SYMBOL(name) decltype(&::name) name = symbol<decltype(&::name)>(#name)
    CERT_SYMBOL(ERR_clear_error);
    CERT_SYMBOL(EC_KEY_new_by_curve_name);
    CERT_SYMBOL(EC_KEY_generate_key);
    CERT_SYMBOL(EC_KEY_free);
    CERT_SYMBOL(EVP_PKEY_new);
    CERT_SYMBOL(EVP_PKEY_set1_EC_KEY);
    CERT_SYMBOL(EVP_PKEY_free);
    CERT_SYMBOL(EVP_sha256);
    CERT_SYMBOL(X509_REQ_new);
    CERT_SYMBOL(X509_REQ_free);
    CERT_SYMBOL(X509_REQ_set_version);
    CERT_SYMBOL(X509_REQ_get_subject_name);
    CERT_SYMBOL(X509_REQ_set_pubkey);
    CERT_SYMBOL(X509_REQ_add1_attr_by_NID);
    CERT_SYMBOL(X509_REQ_sign);
    CERT_SYMBOL(i2d_X509_REQ);
    CERT_SYMBOL(i2d_PrivateKey);
    CERT_SYMBOL(d2i_AutoPrivateKey);
    CERT_SYMBOL(d2i_X509);
    CERT_SYMBOL(X509_free);
    CERT_SYMBOL(X509_check_private_key);
    CERT_SYMBOL(X509_get_pubkey);
    CERT_SYMBOL(X509_verify);
    int (*checkPurpose)(X509 *, int, int) =
        symbol<int (*)(X509 *, int, int)>("X509_check_purpose");
    CERT_SYMBOL(X509_get0_notBefore);
    CERT_SYMBOL(X509_get0_notAfter);
    CERT_SYMBOL(X509_cmp_time);
#undef CERT_SYMBOL
};
Crypto &crypto() {
    static Crypto instance;
    return instance;
}
[[noreturn]] void cryptoError(const std::string &message,
                             const std::source_location &where = std::source_location::current()) {
    const char *reason = crypto().errorReason();
    // Only the library's fixed reason string, never error data, keys or CSR bytes.
    throw Error(reason ? message + ": " + reason : message, where);
}
template <class T, class F> std::shared_ptr<T> owned(T *p, F free, const char *operation) {
    if (!p)
        cryptoError(std::string("Client certificate: ") + operation);
    return {p, free};
}
template <class T, class F> Bytes der(T *p, F encode) {
    const int n = encode(p, nullptr);
    if (n <= 0 || n > 16384)
        cryptoError("Cannot size client key/CSR encoding");
    Bytes result(n);
    auto *at = result.data();
    if (encode(p, &at) != n || at != result.data() + result.size())
        cryptoError("Cannot encode client key/CSR");
    return result;
}
Bytes decode(const Json &record, const char *field) {
    const auto &s = record.at(field).get_ref<const std::string &>();
    if (s.empty() || s.size() > 32768)
        throw Error("Invalid client certificate/key size");
    return unbase64(s, true);
}
} // namespace
Json createCertificateKey() {
    auto &a = crypto();
    a.ERR_clear_error();
    auto ec = owned(a.EC_KEY_new_by_curve_name(NID_X9_62_prime256v1), a.EC_KEY_free,
                    "EC_KEY_new_by_curve_name");
    if (a.EC_KEY_generate_key(ec.get()) != 1)
        cryptoError("Cannot generate client certificate P-256 key");
    auto key = owned(a.EVP_PKEY_new(), a.EVP_PKEY_free, "EVP_PKEY_new");
    auto csr = owned(a.X509_REQ_new(), a.X509_REQ_free, "X509_REQ_new");
    if (a.EVP_PKEY_set1_EC_KEY(key.get(), ec.get()) != 1 ||
        a.X509_REQ_set_version(csr.get(), 0) != 1 ||
        a.X509_REQ_set_pubkey(csr.get(), key.get()) != 1)
        cryptoError("Cannot initialize client certificate CSR");
    auto *name = a.X509_REQ_get_subject_name(csr.get());
    if (!name)
        cryptoError("Cannot get client certificate CSR subject");
    // Subject, P-256/SHA-256 and extensionRequest match the captured G7 CSR.
    for (const auto &[field, value] :
         {std::pair{"CN", "dexcom.com"}, {"O", "Dexcom"}, {"OU", "R&D"},
          {"L", "San Diego"}, {"ST", "California"}, {"C", "US"}})
        if (a.addName(name, field, value) != 1)
            cryptoError(std::string("Cannot set client certificate CSR subject field ") + field);
    // DER Extensions: critical BasicConstraints(cA=TRUE), as in G7's request.
    // The authenticated issuing service determines the actual client cert's
    // constraints; this CSR never becomes a trusted CA or a server trust anchor.
    const unsigned char extension[]{0x30, 0x11, 0x30, 0x0f, 0x06, 0x03, 0x55, 0x1d, 0x13,
                                     0x01, 0x01, 0xff, 0x04, 0x05, 0x30, 0x03, 0x01, 0x01, 0xff};
    if (a.X509_REQ_add1_attr_by_NID(csr.get(), NID_ext_req, V_ASN1_SEQUENCE,
                                   extension, sizeof(extension)) != 1)
        cryptoError("Cannot set client certificate CSR extension request");
    if (a.X509_REQ_sign(csr.get(), key.get(), a.EVP_sha256()) <= 0)
        cryptoError("Cannot sign client certificate CSR");
    auto encoded = base64(der(csr.get(), a.i2d_X509_REQ));
    std::string pem = "-----BEGIN CERTIFICATE REQUEST-----\n";
    for (size_t at = 0; at < encoded.size(); at += 64)
        pem += encoded.substr(at, 64) + "\n";
    pem += "-----END CERTIFICATE REQUEST-----\n";
    return {{"privateKey", base64(der(key.get(), a.i2d_PrivateKey), true)}, {"csr", pem}};
}
ClientCertificate loadCertificate(const Json &record) {
    auto &a = crypto();
    a.ERR_clear_error();
    auto parse = [&](const char *field) {
        const auto data = decode(record, field);
        const auto *at = data.data();
        auto p = owned(a.d2i_X509(nullptr, &at, data.size()), a.X509_free, "decode X.509");
        if (at != data.data() + data.size())
            throw Error("Trailing bytes in client certificate");
        return p;
    };
    ClientCertificate result;
    result.certificate = parse("certificate");
    result.issuer = parse("issuer");
    const auto data = decode(record, "privateKey");
    const auto *at = data.data();
    result.privateKey = owned(a.d2i_AutoPrivateKey(nullptr, &at, data.size()), a.EVP_PKEY_free,
                               "decode private key");
    if (at != data.data() + data.size())
        throw Error("Trailing bytes in client private key");
    if (a.X509_check_private_key(result.certificate.get(), result.privateKey.get()) != 1)
        cryptoError("Client certificate does not match its private key");
    auto issuerKey = owned(a.X509_get_pubkey(result.issuer.get()), a.EVP_PKEY_free,
                            "issuer public key");
    if (a.X509_verify(result.certificate.get(), issuerKey.get()) != 1)
        cryptoError("Invalid client certificate issuer signature");
    if (a.checkPurpose(result.certificate.get(), 1 /* X509_PURPOSE_SSL_CLIENT */, 0) != 1)
        throw Error("Invalid client certificate signature or usage");
    return result;
}
bool ClientCertificate::validFor(int64_t now, int64_t seconds) const {
    auto &a = crypto();
    time_t start = now, end = now + seconds;
    for (auto *cert : {certificate.get(), issuer.get()})
        if (a.X509_cmp_time(a.X509_get0_notBefore(cert), &start) != -1 ||
            a.X509_cmp_time(a.X509_get0_notAfter(cert), &end) != 1)
            return false;
    return true;
}
} // namespace clarity
