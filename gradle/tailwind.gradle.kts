// Tailwind CSS standalone CLI로 CSS를 빌드한다 (Node 불필요, ADR-007).
//   tailwindDownload: 공식 릴리스에서 바이너리를 받아 SHA-256을 확인한다 (.gradle/tailwind/ 에 보관, 커밋하지 않음)
//   tailwindBuild:    src/main/tailwind/app.css → build/generated/tailwind/static/css/app.css (processResources가 의존)
//   tailwindWatch:    개발 중 템플릿·CSS가 바뀌면 바로 다시 빌드 (별도 터미널에서 실행)

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

val tailwindVersion = "4.3.3"

// 공식 릴리스의 sha256sums.txt 값 (https://github.com/tailwindlabs/tailwindcss/releases/tag/v4.3.3).
// 버전을 올릴 때는 반드시 새 릴리스의 sha256sums.txt에서 값을 옮긴다. 받은 파일로 직접 계산한 값을 넣지 않는다
val tailwindChecksums = mapOf(
	"tailwindcss-windows-x64.exe" to "e0e260ce048014e9268f6237ff18f8ccf02cef521cbd0ae04e82c2cdf7aa3955",
	"tailwindcss-linux-x64" to "dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a",
	"tailwindcss-linux-arm64" to "55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195",
)

fun tailwindAssetName(): String {
	val os = System.getProperty("os.name").lowercase()
	val arch = System.getProperty("os.arch").lowercase()
	val x64 = arch == "amd64" || arch == "x86_64"
	val arm64 = arch == "aarch64" || arch == "arm64"
	return when {
		os.contains("windows") && x64 -> "tailwindcss-windows-x64.exe"
		os.contains("linux") && x64 -> "tailwindcss-linux-x64"
		os.contains("linux") && arm64 -> "tailwindcss-linux-arm64"
		else -> throw GradleException("Tailwind 바이너리를 준비하지 않은 OS/CPU입니다: $os/$arch (gradle/tailwind.gradle.kts에 추가 필요)")
	}
}

abstract class TailwindDownload : DefaultTask() {

	@get:Input
	abstract val url: Property<String>

	@get:Input
	abstract val sha256: Property<String>

	@get:OutputFile
	abstract val binary: RegularFileProperty

	@TaskAction
	fun download() {
		val target = binary.get().asFile
		if (target.isFile && sha256Of(target) == sha256.get()) {
			return
		}
		target.parentFile.mkdirs()
		val partial = File(target.parentFile, target.name + ".part")
		logger.lifecycle("Tailwind CLI 다운로드: ${url.get()}")
		URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
		val actual = sha256Of(partial)
		if (actual != sha256.get()) {
			partial.delete()
			throw GradleException("Tailwind 바이너리 체크섬 불일치 (변조 또는 손상 가능). expected=${sha256.get()} actual=$actual")
		}
		Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
		target.setExecutable(true)
		logger.lifecycle("Tailwind CLI 체크섬 확인 완료: $actual")
	}

	private fun sha256Of(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(1 shl 16)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}

val tailwindDownload = tasks.register<TailwindDownload>("tailwindDownload") {
	group = "tailwind"
	description = "Tailwind CSS standalone CLI를 받아 SHA-256을 확인한다"
	val asset = provider { tailwindAssetName() }
	url.set(asset.map { "https://github.com/tailwindlabs/tailwindcss/releases/download/v$tailwindVersion/$it" })
	sha256.set(asset.map { tailwindChecksums.getValue(it) })
	binary.set(layout.projectDirectory.file(asset.map { ".gradle/tailwind/$tailwindVersion/$it" }))
}

val tailwindInput = layout.projectDirectory.file("src/main/tailwind/app.css")
val tailwindTemplates = layout.projectDirectory.dir("src/main/resources/templates")
val tailwindOutputDir = layout.buildDirectory.dir("generated/tailwind")

val tailwindBuild = tasks.register<Exec>("tailwindBuild") {
	group = "tailwind"
	description = "Tailwind로 app.css를 빌드한다"
	dependsOn(tailwindDownload)
	inputs.file(tailwindInput)
	inputs.files(fileTree(tailwindTemplates)).withPropertyName("templates")
	outputs.dir(tailwindOutputDir)
	val binary = tailwindDownload.flatMap { it.binary }
	val output = tailwindOutputDir.map { it.file("static/css/app.css") }
	doFirst {
		executable = binary.get().asFile.absolutePath
		args("-i", tailwindInput.asFile.absolutePath, "-o", output.get().asFile.absolutePath, "--minify")
	}
}

tasks.register<Exec>("tailwindWatch") {
	group = "tailwind"
	description = "개발용: 템플릿·CSS 변경을 감지해 app.css를 다시 빌드한다 (Ctrl+C로 종료)"
	dependsOn(tailwindDownload)
	val binary = tailwindDownload.flatMap { it.binary }
	val output = tailwindOutputDir.map { it.file("static/css/app.css") }
	doFirst {
		executable = binary.get().asFile.absolutePath
		// Gradle은 표준 입력을 연결하지 않으므로 always를 줘야 watch가 바로 끝나지 않는다
		args("-i", tailwindInput.asFile.absolutePath, "-o", output.get().asFile.absolutePath, "--watch=always")
	}
}

// 빌드된 CSS를 리소스로 포함한다 → classpath:/static/css/app.css (processResources가 tailwindBuild에 자동으로 의존)
the<SourceSetContainer>()["main"].resources.srcDir(tailwindBuild.map { tailwindOutputDir.get() })
