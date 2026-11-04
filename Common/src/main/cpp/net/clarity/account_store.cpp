// SPDX-License-Identifier: GPL-3.0-or-later
#include "account_store.hpp"
#include "mapped.hpp"
namespace clarity {
namespace {
template<size_t N> struct Text {
    uint32_t length; // 0 = absent, otherwise byte count + 1 (no terminator).
    char value[N];
    void set(const Json &object, const char *name) {
        if (!object.contains(name)) return;
        const auto &text = object.at(name).get_ref<const std::string &>();
        if (text.size() > N) throw Error("Clarity account field exceeds native storage capacity");
        length = text.size() + 1;
        memcpy(value, text.data(), text.size());
    }
    void get(Json &object, const char *name) const {
        if (!length) return;
        if (length > N + 1) throw Error("Invalid mapped Clarity account field length");
        object[name] = std::string(value, length - 1);
    }
};
#define ACCOUNT_FIELDS(X) \
 X(schema,64) X(account,40) X(appInstanceId,40) X(installationId,40) \
 X(countryCode,8) X(clientId,256) X(clientSecret,256) X(deviceId,256) X(hardwareId,256) \
 X(accessToken,32768) X(refreshToken,32768) \
 X(tokenUrl,1024) X(bulkUrl,1024) X(sessionUrl,1024) X(certificateUrl,1024)
#define RUNTIME_FIELDS(X) \
 X(AppName,256) X(AppVersion,256) X(AppNumber,256) X(DeviceOsName,256) \
 X(DeviceManufacturer,256) X(DeviceModel,256) X(DeviceOsVersion,256)
#define CERT_FIELDS(X) \
 X(account,40) X(hardwareId,256) X(deviceId,256) X(privateKey,8192) \
 X(csr,8192) X(certificate,32768) X(issuer,32768)
#define FIELD(name,size) Text<size> name;
struct CertificateFields { CERT_FIELDS(FIELD) };
struct alignas(8) AccountFields {
    uint32_t present, expiryPresent;
    int64_t expiry;
    ACCOUNT_FIELDS(FIELD)
    RUNTIME_FIELDS(FIELD)
    uint32_t runtimePresent, certificatePresent, pendingPresent, reserved;
    CertificateFields certificate, pending;
};
#undef FIELD
static_assert(sizeof(AccountFields) == 237760);
#define SET(name,size) result.name.set(config,#name);
#define GET(name,size) value.name.get(config,#name);
CertificateFields certificateFields(const Json &config) {
    CertificateFields result{}; CERT_FIELDS(SET) return result;
}
Json certificateJson(const CertificateFields &value) {
    Json config=Json::object(); CERT_FIELDS(GET) return config;
}
AccountFields fields(const Json &config) {
    AccountFields result{};
    if (config.is_null()) return result;
    result.present=1;
    result.expiryPresent=config.contains("tokenExpiresAt");
    result.expiry=config.value("tokenExpiresAt",int64_t(0));
    ACCOUNT_FIELDS(SET)
    if (config.contains("runtimeInfo")) {
        result.runtimePresent=1;
        const auto &runtime=config.at("runtimeInfo");
#define SET_RUNTIME(name,size) result.name.set(runtime,#name);
        RUNTIME_FIELDS(SET_RUNTIME)
#undef SET_RUNTIME
    }
    if (config.contains("mtls")) {
        result.certificatePresent=1; result.certificate=certificateFields(config.at("mtls"));
    }
    if (config.contains("mtlsPending")) {
        result.pendingPresent=1; result.pending=certificateFields(config.at("mtlsPending"));
    }
    return result;
}
Json configuration(const AccountFields &value) {
    if (!value.present) return nullptr;
    Json config=Json::object();
    ACCOUNT_FIELDS(GET)
    if (value.expiryPresent) config["tokenExpiresAt"]=value.expiry;
    if (value.runtimePresent) {
        Json runtime=Json::object();
#define GET_RUNTIME(name,size) value.name.get(runtime,#name);
        RUNTIME_FIELDS(GET_RUNTIME)
#undef GET_RUNTIME
        config["runtimeInfo"]=std::move(runtime);
    }
    if (value.certificatePresent) config["mtls"]=certificateJson(value.certificate);
    if (value.pendingPresent) config["mtlsPending"]=certificateJson(value.pending);
    return config;
}
#undef SET
#undef GET
}
struct AccountStore::Impl {
    MappedCheckpoint<AccountFields> state;
    explicit Impl(const std::string &path):state(path,"G7ACCT01",AccountFields{}) {}
};
AccountStore::AccountStore(const std::string &path):impl(std::make_unique<Impl>(path)) {}
AccountStore::~AccountStore()=default;
Json AccountStore::load() const { return configuration(impl->state.get()); }
void AccountStore::save(const Json &config) { impl->state.save(fields(config)); }
void AccountStore::clear() { impl->state.save(AccountFields{}); }
} // namespace clarity
