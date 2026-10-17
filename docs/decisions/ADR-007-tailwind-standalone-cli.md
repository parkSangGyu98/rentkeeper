# ADR-007: Tailwind CSS는 standalone CLI로 빌드하고 바이너리·결과물은 커밋하지 않는다

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- 화면은 Thymeleaf 서버 렌더링이다(ADR-001). 프런트엔드 프로젝트나 Node.js 도구 체인을 따로 두고 싶지 않다.
- Tailwind는 템플릿에서 실제로 쓰인 클래스만 모아 CSS를 만드는 빌드 단계가 필요하다.
- CSP를 `style-src 'self'`로 둘 계획이라 CDN 스크립트(Play CDN)나 인라인 스타일은 쓸 수 없다.

## 결정
1. Tailwind 공식 **standalone CLI**(단일 실행 파일, Node 불필요) **v4.3.3**을 쓴다 (2026-07-16 릴리스, 나온 지 2주 이상).
2. Gradle 스크립트(`gradle/tailwind.gradle.kts`)가 공식 GitHub 릴리스에서 OS에 맞는 바이너리를 받는다.
   - Windows x64(로컬), Linux x64(CI), Linux arm64(운영 서버 후보 t4g)를 지원한다.
   - 저장 위치: `.gradle/tailwind/4.3.3/` (gitignore 대상, `clean`해도 남는다).
3. **무결성 확인**: 릴리스에 포함된 공식 `sha256sums.txt` 값을 스크립트에 고정한다. 내려받은 파일의 SHA-256이 다르면 파일을 지우고 빌드를 실패시킨다. 버전을 올릴 때는 새 릴리스의 `sha256sums.txt`에서 값을 옮긴다 (받은 파일로 직접 계산한 값을 넣지 않는다).
4. **바이너리(약 107MB)와 빌드된 `app.css`는 커밋하지 않는다.** `tailwindBuild`가 `processResources` 전에 실행되어 `classpath:/static/css/app.css`로 포함된다.
5. 설정은 Tailwind v4 방식대로 CSS 파일 하나(`src/main/tailwind/app.css`)에 둔다. `tailwind.config.js`는 없다.
6. 개발 중에는 별도 터미널에서 `.\gradlew.bat tailwindWatch`를 띄운다. local 프로필은 `build/generated/tailwind/static/`을 정적 파일 경로 맨 앞에 두어 앱 재시작 없이 새 CSS를 쓴다.

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| Node + npm (`tailwindcss` 패키지) | Node 설치·`package.json`·`node_modules` 관리가 추가된다. 1인 백엔드 개발자에게 도구 체인이 하나 더 생긴다 |
| Gradle Node 플러그인 | Node를 자동 설치해 주지만 결국 npm 의존성 관리가 생긴다 |
| Play CDN (브라우저에서 생성) | 운영용이 아니라고 공식 문서가 명시한다. 인라인 스타일을 주입해 CSP와 충돌한다 |
| 바이너리를 저장소에 커밋 | 파일당 약 107MB. 저장소가 커지고 버전 교체 이력이 쌓인다 |
| 빌드된 CSS를 커밋 | 템플릿을 고치고 CSS를 다시 빌드하지 않으면 화면과 어긋난다. 빌드에서 항상 다시 만드는 쪽이 안전하다 |
| 다운로드 플러그인(`de.undercouch.download`) | 체크섬 확인 기능이 있지만 40줄 정도의 직접 구현으로 충분하고, 빌드 플러그인을 하나 줄일 수 있다 |

## 결과
- 처음 빌드할 때와 CI 실행마다 약 107MB를 내려받는다. CI가 느려지면 Actions 캐시를 추가한다.
- Windows Defender가 처음 실행할 때 exe를 검사해 몇 초 늦어질 수 있다.
- 지원하지 않는 OS/CPU(예: macOS)에서는 명확한 오류로 빌드가 멈춘다. 필요하면 `tailwindChecksums`에 항목을 추가한다.
- Tailwind 업그레이드는 Dependabot이 알려 주지 않는다. 버전·체크섬을 수동으로 바꾼다.
