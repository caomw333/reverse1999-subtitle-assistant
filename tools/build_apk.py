from io import BytesIO
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as xml
from zipfile import ZIP_STORED, ZipFile

root = Path(__file__).resolve().parent.parent
os.chdir(root)
tools = Path(".tools")
src = Path("app/src/main")
sdk = tools / "build-tools/android-15"
platform = tools / "platform/android-35/android.jar"
javac = Path(os.environ["SUBTITLE_JDK"]) / "bin/javac.exe"
java = javac.with_name("java.exe")
work = tools / "package"
classes = work / "classes"
generated = work / "generated"
dist = Path("dist")
for directory in (classes, generated, dist):
    directory.mkdir(parents=True, exist_ok=True)


def run(*command):
    subprocess.run([str(arg) for arg in command], check=True)


res = work / "resources.zip"
unsigned_resources = work / "resources.apk"
manifest = work / "AndroidManifest.xml"
android_ns = "http://schemas.android.com/apk/res/android"
xml.register_namespace("android", android_ns)
manifest_tree = xml.parse(src / "AndroidManifest.xml")
manifest_tree.getroot().set("package", "com.local.reverse1999.subtitles")
manifest_tree.getroot().find("application").set(f"{{{android_ns}}}extractNativeLibs", "false")
manifest_tree.write(manifest, encoding="utf-8", xml_declaration=True)
run(sdk / "aapt2.exe", "compile", "--dir", src / "res", "-o", res)
run(sdk / "aapt2.exe", "link", "-I", platform,
    "--manifest", manifest, "--java", generated,
    "--min-sdk-version", "29", "--target-sdk-version", "35",
    "--version-code", "9", "--version-name", "0.3.3",
    "-A", src / "assets", "-0", "zip", "-o", unsigned_resources, res)

archives = sorted(Path("app/libs").glob("*.aar"))
jars = []
for archive in archives:
    jar = work / archive.with_suffix(".jar").name
    with ZipFile(archive) as aar:
        jar.write_bytes(aar.read("classes.jar"))
    jars.append(jar)
classpath = os.pathsep.join(map(str, jars))
bootclasspath = os.pathsep.join(map(str, [sdk / "core-lambda-stubs.jar", platform]))
run(javac, "-source", "8", "-target", "8", "-proc:none", "-Xlint:-options",
    "-bootclasspath", bootclasspath, "-classpath", classpath, "-encoding", "UTF-8", "-d", classes,
    *sorted((src / "java").rglob("*.java")), *sorted(generated.rglob("*.java")))
with ZipFile(work / "app-classes.jar", "w") as archive:
    for cls in classes.rglob("*.class"):
        archive.write(cls, cls.relative_to(classes).as_posix())
run(java, "-cp", sdk / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
    "--min-api", "29", "--lib", platform, "--output", work, work / "app-classes.jar", *jars)

unaligned = work / "unaligned.apk"
abis = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
with ZipFile(unsigned_resources) as resources, ZipFile(unaligned, "w", ZIP_STORED) as apk:
    for entry in resources.infolist():
        apk.writestr(entry, resources.read(entry))
    apk.write(work / "classes.dex", "classes.dex")
    for archive in archives:
        with ZipFile(archive) as aar:
            for entry in aar.namelist():
                parts = entry.split("/")
                if len(parts) == 3 and parts[0] == "jni" and parts[1] in abis and entry.endswith(".so"):
                    apk.writestr("lib/" + "/".join(parts[1:]), aar.read(entry))
            with ZipFile(BytesIO(aar.read("classes.jar"))) as jar:
                for entry in jar.namelist():
                    name = entry.rsplit("/", 1)[-1]
                    if name.upper().startswith(("LICENSE", "NOTICE", "AL2.0", "LGPL")):
                        apk.writestr(f"assets/licenses/{archive.stem}/{name}", jar.read(entry))

aligned = work / "aligned.apk"
run(sdk / "zipalign.exe", "-P", "16", "-f", "4", unaligned, aligned)
key = work / "debug.p12"
if not key.exists():
    run(java.parent / "keytool.exe", "-genkeypair", "-storetype", "PKCS12",
        "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")
apk = dist / "Reverse1999-Subtitles-0.3.3-test.apk"
signer = (java, "-jar", sdk / "lib/apksigner.jar")
run(*signer, "sign", "--ks", key, "--ks-key-alias", "androiddebugkey",
    "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned)
run(*signer, "verify", "--verbose", apk)
run(sdk / "zipalign.exe", "-c", "-P", "16", "4", apk)
run(sdk / "aapt2.exe", "dump", "badging", apk)
print(apk.resolve())
