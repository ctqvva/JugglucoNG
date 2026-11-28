#include "net/ICE/ContextHTTPS.hpp"
#include "net/ICE/TurnTokenResponse.hpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <optional>
#include <openssl/pem.h>

namespace {
// Client identity for the mutual-TLS modes, read from PEM files named by the
// harness. Only borrowed by the request, as Dexcom Clarity does.
struct ClientFiles {
    X509 *certificate=nullptr,*issuer=nullptr;
    EVP_PKEY *key=nullptr;
    ClientFiles() {
        auto open=[](const char *variable) {
            const char *path=std::getenv(variable);
            assert(path);
            FILE *file=std::fopen(path,"r");
            assert(file);
            return file;
        };
        FILE *f=open("CLIENT_CERT");
        certificate=PEM_read_X509(f,nullptr,nullptr,nullptr);
        std::fclose(f);
        f=open("CLIENT_KEY");
        key=PEM_read_PrivateKey(f,nullptr,nullptr,nullptr);
        std::fclose(f);
        f=open("CLIENT_ISSUER");
        issuer=PEM_read_X509(f,nullptr,nullptr,nullptr);
        std::fclose(f);
        assert(certificate&&key&&issuer);
    }
    ~ClientFiles() { X509_free(certificate);X509_free(issuer);EVP_PKEY_free(key); }
};
}

int main(int argc,char **argv) {
    assert(argc==3);
    const std::string mode=argv[2];
    const bool cancellation=mode=="cancel";
    auto cancelled=std::make_shared<std::atomic_bool>(false);
    std::jthread cancel;
    if(cancellation) cancel=std::jthread([cancelled] {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        cancelled->store(true);
    });
    const std::string input="Ttl=600";
    const bool mtls=mode=="mtls"||mode=="mtls-missing";
    std::optional<ClientFiles> files;
    HttpsClientIdentity identity{};
    if(mode=="mtls") {
        files.emplace();
        identity={files->certificate,files->key,files->issuer};
    }
    std::string location="stale";
    const auto begin=std::chrono::steady_clock::now();
    auto [body,status]=ContextHTTPS::getContext().postRequest("localhost",std::stoi(argv[1]),
        "/token",{input.data(),input.size()},"\r\nContent-Type: application/x-www-form-urlencoded",
        {.timeoutMilliseconds=mode=="timeout"||mode=="longpoll"?350:1500,
         .responseTimeoutMilliseconds=mode=="longpoll"?1500:0,.cancelled=cancelled,
         .location=&location,.client=mode=="mtls"?&identity:nullptr});
    const auto elapsed=std::chrono::steady_clock::now()-begin;
    if(mode=="redirect") {
        assert(status==307&&body.empty());
        assert(location=="https://localhost/moved?x=1");
    } else if(mode=="mtls-missing") {
        assert(status==-1&&body.empty());
    } else if(mode=="reject"||mode=="abrupt"||mode=="timeout"||cancellation) {
        assert(status==-1&&body.empty());
        if(mode=="timeout"||cancellation) assert(elapsed<std::chrono::seconds(1));
    } else if(mode=="empty") {
        assert(status==204&&body.empty());
    } else {
        assert(status==201);
        const auto token=TurnTokenReader({body.data(),body.size()}).read();
        assert(token&&token->username=="synthetic-user"&&token->password=="synthetic-password");
        assert(token->ttlSeconds==600);
        // A Location on an interim reply, or none at all, must not surface.
        assert(location.empty());
    }
    (void)mtls;
    std::puts("PASS: complete HTTPS response / bounded failure");
}
