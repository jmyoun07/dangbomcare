package com.dangbomcare.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 소셜 로그인 버튼 — **각 사 브랜드 가이드 준수 지점을 이 파일 한 곳에만 둔다.**
 *
 * [260730] 왜 합쳤나: 이전엔 WelcomeScreen(진입점)과 LoginScreen(이메일 로그인)이 각자
 *   버튼을 따로 그렸다. 그래서 LoginScreen 만 공식 마크로 고쳤을 때 정작 사용자가 먼저 보는
 *   WelcomeScreen 은 자체 제작 'G'(구글 브랜드 블루로 글자를 직접 그림) + 카카오 옐로 위
 *   말풍선 이모지(💬) 상태로 남았다. 둘 다 로고 모방이고, 화면이 갈라져 있으면 또 어긋난다.
 *   → 브랜드 규정은 여기서만 정의하고 두 화면은 크기만 넘긴다.
 *
 * 크기는 화면마다 다르므로([height]/[fontSize]) 파라미터로 받되, **마크 크기는 규정 비율로
 *   자동 계산**한다(아래 [brandMarkSlot]). 화면이 버튼 높이를 바꿔도 비율이 깨지지 않는다.
 */

/**
 * 브랜드 마크가 들어갈 정사각 슬롯 크기.
 *
 * 카카오 공식 버튼(600x90)에서 말풍선 심볼은 34px = 버튼 높이의 **0.378**, 세로 정중앙이다.
 * 우리 심볼 애셋은 41px 캔버스에 34px 심볼이 중앙에 놓인 형태라 캔버스의 0.829 를 차지한다.
 * `ContentScale.Fit` 로 S dp 슬롯에 넣으면 화면상 심볼 높이 = 0.829 * S 이므로
 *   0.829 * S = 0.378 * height  →  S = 0.456 * height.
 * Google 의 `googleg_standard_color_18` 도 공식 버튼에서 대략 같은 비율(0.45)이라 함께 쓴다.
 */
private fun brandMarkSlot(height: Dp): Dp = (height.value * 0.456f).dp

/**
 * Google 로그인 버튼 — Google Identity 브랜딩 가이드라인 준수.
 *
 *  · 문구: 가이드 승인 3종("Sign in / Sign up / Continue with Google") 중 첫 번째의 한국어화.
 *    가이드가 로컬라이즈를 명시적으로 권장한다("permitted and encouraged").
 *  · 마크: 표준 컬러 'G' 만. "you can't change the size or color of the Google 'G' logo" 라
 *    tint 를 걸지 않고 원본을 그대로 그린다. 직접 그린 글자 G 는 규정 위반이다.
 *  · 배경: 가이드가 "appear on a white background" 를 요구 → 버튼 배경 흰색.
 *  · Light 스킴 규정색: Fill #FFFFFF / Stroke #747775 / Font #1F1F1F.
 *
 * 애셋 출처: com.google.android.gms:play-services-base (firebase-messaging/analytics 가
 *   transitive 로 끌어옴) → 별도 다운로드 불필요, 이미 APK 에 포함돼 있다.
 *   AGP 9 는 non-transitive R 이라 앱 R 이 아니라 **라이브러리 네임스페이스의 R** 로 참조해야 한다.
 */
@Composable
fun GoogleLoginButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    fontSize: TextUnit = 14.sp,
    // [260730] 카카오 규정 문구 "카카오 로그인"에 맞춰 "[제공자] 로그인" 형태로 통일
    //   (카카오는 "카카오로 로그인"이 규정 밖이라 반대로 맞출 수 없다).
    text: String = "Google 로그인",
) {
    BrandLoginButton(
        onClick = onClick,
        modifier = modifier,
        height = height,
        fontSize = fontSize,
        markRes = com.google.android.gms.base.R.drawable.googleg_standard_color_18,
        text = text,
        container = Color(0xFFFFFFFF),
        border = Color(0xFF747775),
        label = Color(0xFF1F1F1F),
    )
}

/**
 * 카카오 로그인 버튼 — 카카오 로그인 디자인 가이드 준수.
 *
 *  · 문구: 국문 허용값은 완성형 "카카오 로그인" / 축약형 "로그인" 둘뿐이다.
 *    "카카오로 시작하기" 같은 변형은 규정 밖이다.
 *  · 심볼: 공식 애셋(kakao_login/ko/kakao_login_large_wide.png, 600x90)에서 말풍선 부분만
 *    **크롭**해 res/drawable-{밀도}/kakao_login_symbol.png 로 넣었다. 가이드가
 *    "심볼의 형태, 비율, 색상은 변경할 수 없습니다" 라고 못박아서,
 *    알파를 새로 계산해 배경을 투명화하지 않았다(안티에일리어싱 경계 픽셀을 우리가 재구성하게 되므로).
 *    원본의 #FEE500 배경을 그대로 둔 채 잘라냈고 버튼 배경이 같은 #FEE500 이라 이음선이 없다.
 *    (5개 밀도 전부 테두리 1px 링이 정확히 #FEE500 인지 검증했다.)
 *    카카오가 배포하는 최대 래스터에서 심볼은 36x34px 뿐이다 — PSD 안 버튼도 같은 크기다.
 *    완성형 버튼 이미지를 통째로 쓰면 폭을 채우느라 1.6~2.1배 확대돼 **글자까지 흐려지고
 *    시스템 글꼴 확대(fontScale)도 안 먹는다**. 시니어 대상이라 텍스트는 앱 폰트로 그린다.
 *  · 색: 컨테이너 #FEE500, 레이블 #000000 85%(=0xD9 알파). 규정값 그대로.
 *  · radius: 가이드가 12px 로 지정 → [BRAND_RADIUS].
 */
@Composable
fun KakaoLoginButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    fontSize: TextUnit = 14.sp,
    text: String = "카카오 로그인",
) {
    BrandLoginButton(
        onClick = onClick,
        modifier = modifier,
        height = height,
        fontSize = fontSize,
        markRes = com.dangbomcare.app.R.drawable.kakao_login_symbol,
        text = text,
        container = Color(0xFFFEE500),
        border = Color(0xFFFEE500),
        label = Color(0xD9000000),
    )
}

/** 카카오 가이드가 지정한 컨테이너 radius. Google 쪽도 같이 맞춰 두 버튼 모양을 통일한다. */
private val BRAND_RADIUS = 12.dp

@Composable
private fun BrandLoginButton(
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    fontSize: TextUnit,
    markRes: Int,
    text: String,
    container: Color,
    border: Color,
    label: Color,
) {
    val slot = brandMarkSlot(height)
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(BRAND_RADIUS),
        colors = ButtonDefaults.buttonColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        // [260730] 마크는 왼쪽 고정, 문구는 버튼 가운데 정렬(이메일 로그인 버튼과 통일).
        //   카카오 공식 버튼도 심볼 좌측 + 문구 중앙 배치라 규정에도 부합한다.
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(markRes),
                // 버튼 문구가 이미 낭독되므로 마크는 중복 낭독하지 않는다.
                contentDescription = null,
                // 두 사 모두 마크 왜곡을 금지한다 → Fit 으로 종횡비만 유지, tint 없음.
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterStart).size(slot),
            )
            Text(
                text,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = label,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
