/*      Part of JugglucoNG.                                                          */
/*                                                                                   */
/*      The journal's contribution to the LibreView measurement log.                 */
/*                                                                                   */
/*      LibreView's food, insulin and generic arrays used to be written only from the */
/*      legacy native Numdata store. The Compose journal keeps its entries in Room and*/
/*      never writes that store, so nothing a user recorded reached LibreView --      */
/*      the "send amounts" switch was wired to an empty source. Nightscout was moved  */
/*      off Numdata the same way (see JournalTreatmentUploader); this is the LibreView*/
/*      half of that move.                                                            */
/*                                                                                   */
/*      The payload buffer is sized before it is filled, so the entries are fetched   */
/*      once per document and held here: prepare() renders and caches them, write()   */
/*      copies one array out, and commit()/discard() runs after the POST is answered. */

#include <jni.h>
#include <string>
#include "libreview.hpp"
#include "logs.hpp"

extern JNIEnv *getenv();

namespace {

jclass journalclass = nullptr;
bool journallookupfailed = false;

std::string pendingfood, pendinginsulin, pendingnotes;

bool ensurejournalclass(JNIEnv *env) {
    if (journalclass != nullptr)
        return true;
    if (journallookupfailed)
        return false;
    constexpr const char classstr[] = "tk/glucodata/LibreviewJournal";
    if (jclass cl = env->FindClass(classstr)) {
        journalclass = (jclass)env->NewGlobalRef(cl);
        env->DeleteLocalRef(cl);
        if (journalclass != nullptr)
            return true;
        }
    if (env->ExceptionCheck())
        env->ExceptionClear();
    journallookupfailed = true;
    LOGGER("FindClass(%s) failed\n", classstr);
    return false;
    }

//The rendered entries are ASCII by construction -- LibreviewJournalTransfer escapes every
//non-ASCII character -- so JNI's modified UTF-8 and the bytes that go on the wire are the
//same thing, and the length reported here is the length written later.
std::string fetchfragment(JNIEnv *env, const char *name) {
    jmethodID method = env->GetStaticMethodID(journalclass, name, "()Ljava/lang/String;");
    if (method == nullptr) {
        if (env->ExceptionCheck())
            env->ExceptionClear();
        return {};
        }
    auto jstr = (jstring)env->CallStaticObjectMethod(journalclass, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
        }
    if (jstr == nullptr)
        return {};
    std::string uit;
    if (const char *chars = env->GetStringUTFChars(jstr, nullptr)) {
        uit.assign(chars, env->GetStringUTFLength(jstr));
        env->ReleaseStringUTFChars(jstr, chars);
        }
    env->DeleteLocalRef(jstr);
    return uit;
    }

void callvoid(const char *name) {
    auto env = getenv();
    if (env == nullptr || !ensurejournalclass(env))
        return;
    jmethodID method = env->GetStaticMethodID(journalclass, name, "()V");
    if (method == nullptr) {
        if (env->ExceptionCheck())
            env->ExceptionClear();
        return;
        }
    env->CallStaticVoidMethod(journalclass, method);
    if (env->ExceptionCheck())
        env->ExceptionClear();
    }

} // namespace

int libreviewJournalPrepare(bool libre3) {
    pendingfood.clear();
    pendinginsulin.clear();
    pendingnotes.clear();
    auto env = getenv();
    if (env == nullptr || !ensurejournalclass(env))
        return 0;
    jmethodID prepare = env->GetStaticMethodID(journalclass, "prepare", "(Z)I");
    if (prepare == nullptr) {
        if (env->ExceptionCheck())
            env->ExceptionClear();
        return 0;
        }
    const int announced = env->CallStaticIntMethod(journalclass, prepare, (jboolean)libre3);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
        }
    if (announced <= 0)
        return 0;
    pendingfood = fetchfragment(env, "foodEntries");
    pendinginsulin = fetchfragment(env, "insulinEntries");
    pendingnotes = fetchfragment(env, "noteEntries");
    //Size from what was actually handed over, never from what Java announced: the buffer
    //has to hold the bytes this file will copy, whatever the other side counted.
    const int total = (int)(pendingfood.size() + pendinginsulin.size() + pendingnotes.size());
    LOGGER("libreview journal entries: food=%zu insulin=%zu notes=%zu\n", pendingfood.size(),
           pendinginsulin.size(), pendingnotes.size());
    return total;
    }

int libreviewJournalWrite(char *out, const int kind) {
    const std::string &from = kind == libreviewJournalFood     ? pendingfood
                              : kind == libreviewJournalInsulin ? pendinginsulin
                                                                : pendingnotes;
    if (from.empty())
        return 0;
    memcpy(out, from.data(), from.size());
    return (int)from.size();
    }

void libreviewJournalCommit() {
    pendingfood.clear();
    pendinginsulin.clear();
    pendingnotes.clear();
    callvoid("commit");
    }

void libreviewJournalDiscard() {
    pendingfood.clear();
    pendinginsulin.clear();
    pendingnotes.clear();
    callvoid("discard");
    }
