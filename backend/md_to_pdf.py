"""당당케어 제안서 마크다운 → PDF 변환 (Chrome headless 활용)"""
import os
import subprocess
import sys
import tempfile

import markdown


def main():
    # 명령줄에서 파일 이름 + (선택) 폴더 지정 가능
    #   python md_to_pdf.py <basename> [base_dir]
    if len(sys.argv) >= 2:
        basename = sys.argv[1]
    else:
        basename = "당봄케어_백엔드_비용비교_제안서"
    base_dir = sys.argv[2] if len(sys.argv) >= 3 else r"C:\Users\rudtn\OneDrive\바탕 화면\todo\watch"
    src = rf"{base_dir}\{basename}.md"
    out_pdf = rf"{base_dir}\{basename}.pdf"

    with open(src, "r", encoding="utf-8") as f:
        md_content = f.read()

    # 마크다운 → HTML 본문
    html_body = markdown.markdown(
        md_content,
        extensions=["tables", "fenced_code", "toc", "nl2br"],
    )

    # 한글 친화 CSS + 인쇄 레이아웃 (A4)
    full_html = f"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>당당케어 백엔드 비용 비교 제안서</title>
<style>
  @page {{
    size: A4;
    margin: 20mm 18mm 22mm 18mm;
    @bottom-right {{
      content: counter(page) " / " counter(pages);
      font-size: 10pt;
      color: #888;
    }}
  }}
  * {{ box-sizing: border-box; }}
  body {{
    font-family: 'Malgun Gothic', 'Noto Sans KR', '맑은 고딕', sans-serif;
    color: #1A2530;
    line-height: 1.55;
    font-size: 11pt;
    margin: 0;
    padding: 0;
  }}
  h1 {{
    font-size: 22pt;
    color: #2D5F3F;
    border-bottom: 3px solid #2D5F3F;
    padding-bottom: 8px;
    margin-top: 0;
    page-break-before: auto;
  }}
  h2 {{
    font-size: 16pt;
    color: #1F4A2E;
    border-bottom: 2px solid #A8D55C;
    padding-bottom: 6px;
    margin-top: 24px;
    page-break-after: avoid;
  }}
  h3 {{
    font-size: 13pt;
    color: #2D5F3F;
    margin-top: 18px;
    page-break-after: avoid;
  }}
  h4 {{
    font-size: 12pt;
    color: #4A6670;
    margin-top: 14px;
  }}
  table {{
    border-collapse: collapse;
    width: 100%;
    margin: 12px 0;
    font-size: 10pt;
    page-break-inside: avoid;
  }}
  th {{
    background-color: #2D5F3F;
    color: white;
    font-weight: bold;
    padding: 8px 10px;
    text-align: left;
    border: 1px solid #1F4A2E;
  }}
  td {{
    padding: 7px 10px;
    border: 1px solid #BFCAB9;
    vertical-align: top;
  }}
  tr:nth-child(even) td {{
    background-color: #F5F3F0;
  }}
  tr:nth-child(odd) td {{
    background-color: #FAFAF8;
  }}
  strong {{
    color: #1F4A2E;
    font-weight: 700;
  }}
  ul, ol {{
    margin: 8px 0 8px 0;
    padding-left: 22px;
  }}
  li {{
    margin-bottom: 4px;
  }}
  hr {{
    border: none;
    border-top: 1px solid #BFCAB9;
    margin: 24px 0;
  }}
  blockquote {{
    background: #F0F5F0;
    border-left: 4px solid #A8D55C;
    padding: 8px 14px;
    margin: 12px 0;
    color: #3F4A3D;
    font-style: italic;
    font-size: 10pt;
  }}
  code {{
    background: #F5F3F0;
    padding: 2px 6px;
    border-radius: 3px;
    font-family: 'Consolas', 'D2Coding', monospace;
    font-size: 9pt;
    color: #1F4A2E;
  }}
  pre {{
    background: #1A2530;
    color: #E0E7E0;
    padding: 12px;
    border-radius: 6px;
    overflow-x: auto;
    font-size: 9pt;
    line-height: 1.4;
    page-break-inside: avoid;
  }}
  pre code {{
    background: transparent;
    color: inherit;
    padding: 0;
  }}
  /* 표 안의 emoji는 조금 작게 */
  td .emoji {{ font-size: 11pt; }}
  /* 항목 강조 */
  p strong:first-child {{
    color: #2D5F3F;
  }}
  /* Cover-like first section */
  body > h1:first-child {{
    text-align: center;
    margin-top: 40px;
    font-size: 28pt;
    border: none;
    padding: 20px;
    background: linear-gradient(135deg, #2D5F3F 0%, #4A8060 100%);
    color: white;
    border-radius: 12px;
  }}
</style>
</head>
<body>
{html_body}
</body>
</html>
"""

    # 임시 HTML 파일 생성
    tmp_html = tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", suffix=".html", delete=False
    )
    tmp_html.write(full_html)
    tmp_html.close()

    # Chrome headless로 PDF 출력
    chrome = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
    file_uri = "file:///" + tmp_html.name.replace("\\", "/")

    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-pdf-header-footer",
        f"--print-to-pdf={out_pdf}",
        "--no-margins",
        "--virtual-time-budget=10000",
        file_uri,
    ]
    print("실행:", " ".join(cmd[:3]), "...")
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        print("STDOUT:", result.stdout[:500])
        print("STDERR:", result.stderr[:500])

    os.unlink(tmp_html.name)

    if os.path.exists(out_pdf):
        size_kb = os.path.getsize(out_pdf) / 1024
        print(f"[OK] PDF 생성 완료: {out_pdf}")
        print(f"     파일 크기: {size_kb:.1f} KB")
    else:
        print("[FAIL] PDF 생성 실패")
        sys.exit(1)


if __name__ == "__main__":
    main()
