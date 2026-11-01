package tk.glucodata.share.lumiflex

object LumiflexCloudConstants {

    const val BASE_URL = "https://cloud.lumiflex.ru"

    const val API_PREFIX = "/api-x"

    const val VENDOR_VERSION = "2.4.0"

    const val APP_INFO = "com.microtech.aidexx.lumi.mmoll,2.4.0"

    const val BRAND = "lumi"

    const val HEADER_VERSION = "version"
    const val HEADER_BRAND = "brand"
    const val HEADER_TOKEN = "x-token"
    const val HEADER_ENCRYPTION = "encryption"
    const val HEADER_APP_VERSION_CHECK = "app-version-check"
    const val HEADER_APP_INFO = "app-info"
    const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"

    const val VERSION_HEADER_VALUE = "ru"
    const val ENCRYPTION_ENABLED = "enabled"

    const val PATH_LOGIN = "/user/loginByPassword"
    const val PATH_USER_INFO = "/user/getUserInfo"
    const val PATH_LOGOUT = "/user/logout"
    const val PATH_DEVICE_REGISTER = "/cgmDevice/userDeviceRegister"
    const val PATH_CGM_SAVE = "/cgmRecord/saveCgmRecord"
    const val PATH_FOLLOWER_LIST = "/userAuthorization/findUserAuthorizationList"
    const val PATH_FOLLOWER_SAVE = "/userAuthorization/saveOrUpdateUserAuthorization"
    const val PATH_FOLLOWER_UPDATE = "/userAuthorization/updateAuthorizationInfo"
    const val PATH_FOLLOWER_BY_ID = "/userAuthorization/findAuthorizationInfoById"
    const val PATH_FOLLOWER_DELETE = "/userAuthorization/deleteByIds"

    const val BATCH_SIZE = 100
    const val QUEUE_CAP = 5000
    const val RETRY_LIMIT = 3

    // The official client ships this pair; the public half encrypts requests, the private half
    // decrypts responses. They are not a matching pair by design.
    const val RSA_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCifgLBAr2MhgWLBJWtTuhtGC4f47eb5xs1Ys/GvYjS77h6aX9Zw2HIpovVRLITdd0bMjD4/VHnrC1bZOD/OeXchOTmjrVlIdKVeJK4RIrYc7oDSlWUYkBKDRqWLEUXS2CS0Fc1X6wa2Pub7G9e3ukV+orrJkMC0+YTEgn18iLUBQIDAQAB"

    const val RSA_PRIVATE_KEY =
        "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAI4fCLavIlpWS8HEyMS6YIT3KX0Udg7ny/6yUJ65OSP5XRLUiRGYufd2w1wastUltLuI0c/ffP/sHfYZhcIOykwyR8twh6spkuD/R1mjirV09Fl4jG/wChK9ce+VFs9mqNs4k61jWiGPo4sjofL3LbRvXi0478pK3IaHXRVuPhTbAgMBAAECgYAbDqN9DDixFC7WFB3Y/laz2c5nRsb3LYAz3ETnAwJy692hYR87X2VATLcCyP50//m5nmG7fqPwoUZO/Sru+BvhPgNU1T1zaY/q/FihkZJQfX2DErHvJq69tFI/wr8V80185RN/6W2eCsMnbTAHdUlZNyBSqjLEfNSkYwUOPfqvoQJBAJ+PprYac0YFuJXollvRlTiMDRPkW2IJ9wVdRZ5XyePk+2CFMkj7DDQE7Yxt2sAVxeLIXHK2oIlpO1oKJ/2rudECQQDkBPcQg2e3KitCFib4xfLAiqyzA4KzrFjMlDNZzx0ZJrmRGRv79Ahcw7pzvU3XlloX7kWXeZhlSC5wSOjSkOLrAkAs/2PyYw8zJhXiIM+WYUqFwelGPNYQleLV3nHtr7A91quVkrqVnkbYvLMJMUnJddpylDKOHzayybx1LfwNK8qhAkEAqD4RnhmXGUsACA8YSk6kQvUPYDai5bew/aIMEaQEMF6mZS1FvK3ezbONi4COQxgo+adRqeaKT5Dm0uq4/0TwaQJAXVBeajTN4i3WN6Fr0DCuFRJDEWvYFdCVDgXvKtSVIheFWwst/rFwUjoOKndVLaDOKfnorKioPsIYd/EQ/z8SQw=="
}
