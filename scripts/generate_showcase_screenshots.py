#!/usr/bin/env python3
import os
import subprocess
import tempfile

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENSHOT_DIR = os.path.join(BASE_DIR, "screenshot")
BG_DIR = os.path.join(SCREENSHOT_DIR, "bg")

BG_AURORA = os.path.join(BG_DIR, "bg_aurora.jpg")
BG_MORAINE = os.path.join(BG_DIR, "bg_moraine.jpg")
BG_EARTH = os.path.join(BG_DIR, "bg_earth.jpg")

COMMON_CSS = """
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    user-select: none;
    -webkit-font-smoothing: antialiased;
}
body {
    width: 1920px;
    height: 1080px;
    overflow: hidden;
    background-color: #05070A;
    font-family: "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
    color: #FFFFFF;
    position: relative;
}

/* Authentic Public Domain Landscape Video Background (NASA / Wikimedia Commons) */
.video-canvas {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background-size: cover;
    background-position: center center;
    background-repeat: no-repeat;
    overflow: hidden;
}
.video-overlay {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.2) 0%, rgba(0, 0, 0, 0.45) 100%);
    pointer-events: none;
}
.watermark {
    position: absolute;
    top: 36px;
    right: 48px;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 2px;
    color: rgba(255, 255, 255, 0.65);
    background: rgba(0, 0, 0, 0.45);
    padding: 6px 14px;
    border-radius: 20px;
    backdrop-filter: blur(8px);
    border: 1px solid rgba(255, 255, 255, 0.15);
    display: flex;
    align-items: center;
    gap: 8px;
    z-index: 10;
}
.watermark-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #00E676;
    box-shadow: 0 0 8px #00E676;
}

/* Reusable UI Components */
.pill-num {
    background: rgba(0, 229, 255, 0.16);
    border: 1px solid rgba(0, 229, 255, 0.4);
    color: #00E5FF;
    font-family: monospace;
    font-weight: 700;
    font-size: 11px;
    padding: 2px 7px;
    border-radius: 6px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
}
.logo-box {
    width: 38px;
    height: 22px;
    background: rgba(255, 255, 255, 0.12);
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 5px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.5px;
    color: #F1F5F9;
}
.badge-live {
    background: rgba(0, 230, 118, 0.20);
    border: 1px solid rgba(0, 230, 118, 0.55);
    color: #00E676;
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 6px;
    display: inline-flex;
    align-items: center;
    gap: 4px;
}
.badge-catchup {
    background: rgba(255, 122, 0, 0.20);
    border: 1px solid rgba(255, 122, 0, 0.55);
    color: #FF7A00;
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 6px;
    display: inline-flex;
    align-items: center;
}
.badge-count {
    background: rgba(255, 255, 255, 0.12);
    border: 1px solid rgba(255, 255, 255, 0.18);
    color: #CBD5E1;
    font-size: 10px;
    padding: 2px 6px;
    border-radius: 10px;
    font-family: monospace;
}
"""

def render_html_to_png(html_content, output_png):
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False) as f:
        f.write(html_content)
        temp_html = f.name
    
    cmd = [
        "google-chrome",
        "--headless",
        "--disable-gpu",
        "--hide-scrollbars",
        "--window-size=1920,1080",
        f"--screenshot={output_png}",
        f"file://{temp_html}"
    ]
    subprocess.run(cmd, check=True)
    os.remove(temp_html)
    print(f"Rendered: {output_png}")

# ==============================================================================
# 1. Channel_list.png: 频道列表 + 3天节目单 (双栏侧边栏，极光背景)
# ==============================================================================
def generate_channel_list():
    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
{COMMON_CSS}

.guide-container {{
    position: absolute;
    top: 0;
    left: 0;
    bottom: 0;
    display: flex;
    height: 100%;
    z-index: 5;
}}

/* Channel column: 250px */
.channel-column {{
    width: 250px;
    background: rgba(13, 17, 26, 0.88);
    border-right: 1px solid rgba(255, 255, 255, 0.10);
    padding: 18px 0;
    display: flex;
    flex-direction: column;
    box-shadow: 12px 0 30px rgba(0, 0, 0, 0.45);
}}
.col-header {{
    font-size: 16px;
    font-weight: 700;
    color: #00E5FF;
    padding: 8px 18px 16px;
    display: flex;
    align-items: center;
    gap: 8px;
}}
.channel-list {{
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 0 10px;
}}
.channel-item {{
    height: 58px;
    padding: 0 12px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    gap: 10px;
    background: rgba(255, 255, 255, 0.04);
    border: 1.5px solid transparent;
}}
.channel-item.focused {{
    background: rgba(0, 229, 255, 0.16);
    border: 1.5px solid #00E5FF;
    box-shadow: 0 0 16px rgba(0, 229, 255, 0.35);
}}
.channel-item .info {{
    flex: 1;
    overflow: hidden;
}}
.channel-item .name {{
    font-size: 14px;
    font-weight: 600;
    color: #F1F5F9;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.channel-item.focused .name {{
    color: #00E5FF;
    font-weight: 700;
}}
.channel-item .now-prog {{
    font-size: 11px;
    color: #94A3B8;
    margin-top: 2px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.channel-item.focused .now-prog {{
    color: #E2E8F0;
}}

/* Program column: 390px */
.program-column {{
    width: 390px;
    background: rgba(8, 13, 20, 0.90);
    border-right: 1px solid rgba(255, 255, 255, 0.10);
    display: flex;
    flex-direction: column;
    padding: 16px 12px;
    box-shadow: 16px 0 35px rgba(0, 0, 0, 0.5);
}}

/* 3-day Segmented Tab Bar */
.date-tabs-bar {{
    height: 40px;
    background: rgba(255, 255, 255, 0.06);
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 20px;
    display: flex;
    align-items: center;
    padding: 3px;
    margin-bottom: 14px;
}}
.date-tab {{
    flex: 1;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    color: #94A3B8;
    border-radius: 16px;
    font-weight: 500;
}}
.date-tab.active {{
    flex: 1.3;
    background: rgba(0, 229, 255, 0.18);
    border: 1px solid #00E5FF;
    color: #00E5FF;
    font-weight: 700;
    font-size: 12px;
}}

.program-list {{
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
}}
.program-item {{
    height: 44px;
    padding: 0 12px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 13px;
}}
.program-item.current {{
    background: rgba(0, 229, 255, 0.10);
    border: 1px solid rgba(0, 229, 255, 0.45);
}}
.program-time {{
    font-size: 12px;
    color: #94A3B8;
    font-family: monospace;
    width: 82px;
}}
.program-item.current .program-time {{
    color: #00E5FF;
    font-weight: 700;
}}
.program-title {{
    flex: 1;
    color: #E2E8F0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.program-item.current .program-title {{
    color: #00E5FF;
    font-weight: 700;
}}
</style>
</head>
<body>
<div class="video-canvas" style="background-image: url('file://{BG_AURORA}');">
    <div class="video-overlay"></div>
    <div class="watermark">
        <div class="watermark-dot"></div>
        LiteTV · 极地秘境 4K
    </div>
</div>

<div class="guide-container">
    <!-- Channel Column -->
    <div class="channel-column">
        <div class="col-header">
            <span>全部频道</span>
            <span class="badge-count">128</span>
        </div>
        <div class="channel-list">
            <div class="channel-item">
                <span class="pill-num">01</span>
                <div class="logo-box">4K</div>
                <div class="info">
                    <div class="name">综合频道 4K</div>
                    <div class="now-prog">早间全球资讯</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">02</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">财经天地 HD</div>
                    <div class="now-prog">全球宏观视点与早盘</div>
                </div>
            </div>
            <div class="channel-item focused">
                <span class="pill-num">03</span>
                <div class="logo-box" style="border-color:#00E5FF; color:#00E5FF;">4K</div>
                <div class="info">
                    <div class="name">纪录探索 4K</div>
                    <div class="now-prog">自然奇观：极地秘境</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">04</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">经典影院 HD</div>
                    <div class="now-prog">环球名作展映</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">05</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">体育赛事 HD</div>
                    <div class="now-prog">2026 世界锦标赛</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">06</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">地理探索 HD</div>
                    <div class="now-prog">秘境寻踪 · 季风篇</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">07</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">少儿动画 HD</div>
                    <div class="now-prog">奇幻自然大旅行</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">08</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">音乐经典 HD</div>
                    <div class="now-prog">交响乐大师名篇</div>
                </div>
            </div>
        </div>
    </div>

    <!-- Program Column -->
    <div class="program-column">
        <div class="date-tabs-bar">
            <div class="date-tab">昨日 11-07</div>
            <div class="date-tab active">今日 11-08 · LIVE</div>
            <div class="date-tab">明日 11-09</div>
        </div>
        <div class="program-list">
            <div class="program-item">
                <span class="program-time">08:30-10:00</span>
                <span class="program-title">深蓝大洋：珊瑚之谜</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item">
                <span class="program-time">10:00-11:30</span>
                <span class="program-title">非洲荒原：生命奇迹</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item">
                <span class="program-time">11:30-13:00</span>
                <span class="program-title">亚马逊雨林：未解之境</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item">
                <span class="program-time">13:00-15:30</span>
                <span class="program-title">喜马拉雅：冰雪之巅</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item current">
                <span class="program-time">15:30-17:30</span>
                <span class="program-title">自然奇观：极地秘境</span>
                <span class="badge-live">● LIVE</span>
            </div>
            <div class="program-item">
                <span class="program-time">17:30-19:00</span>
                <span class="program-title">微观地球：昆虫世界</span>
            </div>
            <div class="program-item">
                <span class="program-time">19:00-20:30</span>
                <span class="program-title">地球脉动：季节轮回</span>
            </div>
            <div class="program-item">
                <span class="program-time">20:30-22:00</span>
                <span class="program-title">史前巨兽：化石密码</span>
            </div>
            <div class="program-item">
                <span class="program-time">22:00-23:30</span>
                <span class="program-title">宇宙空间：星际穿越</span>
            </div>
        </div>
    </div>
</div>
</body>
</html>
"""
    render_html_to_png(html, os.path.join(SCREENSHOT_DIR, "Channel_list.png"))

# ==============================================================================
# 2. Channel_list_g.png: 完整三栏模式 (分组 176px + 频道栏 + 节目栏，雪山湖泊背景)
# ==============================================================================
def generate_channel_list_grouped():
    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
{COMMON_CSS}

.guide-container {{
    position: absolute;
    top: 0;
    left: 0;
    bottom: 0;
    display: flex;
    height: 100%;
    z-index: 5;
}}

/* Group column: 176px */
.group-column {{
    width: 176px;
    background: rgba(17, 22, 29, 0.85);
    border-right: 1px solid rgba(255, 255, 255, 0.10);
    padding: 18px 8px;
    display: flex;
    flex-direction: column;
    box-shadow: 10px 0 25px rgba(0, 0, 0, 0.4);
}}
.group-header {{
    font-size: 15px;
    font-weight: 700;
    color: #00E5FF;
    padding: 8px 12px 16px;
}}
.group-list {{
    display: flex;
    flex-direction: column;
    gap: 4px;
}}
.group-item {{
    height: 48px;
    padding: 0 12px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: rgba(255, 255, 255, 0.03);
    border: 1.5px solid transparent;
}}
.group-item.focused {{
    background: rgba(0, 229, 255, 0.16);
    border: 1.5px solid #00E5FF;
    box-shadow: 0 0 14px rgba(0, 229, 255, 0.35);
}}
.group-name {{
    font-size: 13px;
    font-weight: 600;
    color: #CBD5E1;
}}
.group-item.focused .group-name {{
    color: #00E5FF;
    font-weight: 700;
}}
.group-badge {{
    background: rgba(255, 255, 255, 0.10);
    border: 1px solid rgba(255, 255, 255, 0.16);
    color: #94A3B8;
    font-size: 10px;
    padding: 1px 6px;
    border-radius: 8px;
    font-family: monospace;
}}
.group-item.focused .group-badge {{
    background: #00E5FF;
    color: #05070B;
    font-weight: 800;
}}

/* Channel column: 250px */
.channel-column {{
    width: 250px;
    background: rgba(13, 17, 26, 0.88);
    border-right: 1px solid rgba(255, 255, 255, 0.10);
    padding: 18px 0;
    display: flex;
    flex-direction: column;
    box-shadow: 12px 0 30px rgba(0, 0, 0, 0.45);
}}
.col-header {{
    font-size: 16px;
    font-weight: 700;
    color: #00E5FF;
    padding: 8px 18px 16px;
    display: flex;
    align-items: center;
    gap: 8px;
}}
.channel-list {{
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 0 10px;
}}
.channel-item {{
    height: 58px;
    padding: 0 12px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    gap: 10px;
    background: rgba(255, 255, 255, 0.04);
    border: 1.5px solid transparent;
}}
.channel-item.focused {{
    background: rgba(0, 229, 255, 0.16);
    border: 1.5px solid #00E5FF;
    box-shadow: 0 0 16px rgba(0, 229, 255, 0.35);
}}
.channel-item .info {{
    flex: 1;
    overflow: hidden;
}}
.channel-item .name {{
    font-size: 14px;
    font-weight: 600;
    color: #F1F5F9;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.channel-item.focused .name {{
    color: #00E5FF;
    font-weight: 700;
}}
.channel-item .now-prog {{
    font-size: 11px;
    color: #94A3B8;
    margin-top: 2px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.channel-item.focused .now-prog {{
    color: #E2E8F0;
}}

/* Program column: 390px */
.program-column {{
    width: 390px;
    background: rgba(8, 13, 20, 0.90);
    border-right: 1px solid rgba(255, 255, 255, 0.10);
    display: flex;
    flex-direction: column;
    padding: 16px 12px;
    box-shadow: 16px 0 35px rgba(0, 0, 0, 0.5);
}}
.date-tabs-bar {{
    height: 40px;
    background: rgba(255, 255, 255, 0.06);
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 20px;
    display: flex;
    align-items: center;
    padding: 3px;
    margin-bottom: 14px;
}}
.date-tab {{
    flex: 1;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    color: #94A3B8;
    border-radius: 16px;
    font-weight: 500;
}}
.date-tab.active {{
    flex: 1.3;
    background: rgba(0, 229, 255, 0.18);
    border: 1px solid #00E5FF;
    color: #00E5FF;
    font-weight: 700;
    font-size: 12px;
}}
.program-list {{
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
}}
.program-item {{
    height: 44px;
    padding: 0 12px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 13px;
}}
.program-item.current {{
    background: rgba(0, 229, 255, 0.10);
    border: 1px solid rgba(0, 229, 255, 0.45);
}}
.program-time {{
    font-size: 12px;
    color: #94A3B8;
    font-family: monospace;
    width: 82px;
}}
.program-item.current .program-time {{
    color: #00E5FF;
    font-weight: 700;
}}
.program-title {{
    flex: 1;
    color: #E2E8F0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}}
.program-item.current .program-title {{
    color: #00E5FF;
    font-weight: 700;
}}
</style>
</head>
<body>
<div class="video-canvas" style="background-image: url('file://{BG_MORAINE}');">
    <div class="video-overlay"></div>
    <div class="watermark">
        <div class="watermark-dot"></div>
        LiteTV · 自然探索 HD
    </div>
</div>

<div class="guide-container">
    <!-- Group Column (Expanded: 176px) -->
    <div class="group-column">
        <div class="group-header">频道分组</div>
        <div class="group-list">
            <div class="group-item">
                <span class="group-name">我的收藏</span>
                <span class="group-badge">8</span>
            </div>
            <div class="group-item focused">
                <span class="group-name">综合频道</span>
                <span class="group-badge">16</span>
            </div>
            <div class="group-item">
                <span class="group-name">影视剧场</span>
                <span class="group-badge">24</span>
            </div>
            <div class="group-item">
                <span class="group-name">纪录探索</span>
                <span class="group-badge">12</span>
            </div>
            <div class="group-item">
                <span class="group-name">体育赛事</span>
                <span class="group-badge">15</span>
            </div>
            <div class="group-item">
                <span class="group-name">新闻资讯</span>
                <span class="group-badge">10</span>
            </div>
            <div class="group-item">
                <span class="group-name">全部频道</span>
                <span class="group-badge">128</span>
            </div>
        </div>
    </div>

    <!-- Channel Column -->
    <div class="channel-column">
        <div class="col-header">
            <span>综合频道</span>
            <span class="badge-count">16</span>
        </div>
        <div class="channel-list">
            <div class="channel-item">
                <span class="pill-num">01</span>
                <div class="logo-box">4K</div>
                <div class="info">
                    <div class="name">综合频道 4K</div>
                    <div class="now-prog">早间全球资讯</div>
                </div>
            </div>
            <div class="channel-item focused">
                <span class="pill-num">02</span>
                <div class="logo-box" style="border-color:#00E5FF; color:#00E5FF;">4K</div>
                <div class="info">
                    <div class="name">纪录天地 4K</div>
                    <div class="now-prog">自然奇观：极地秘境</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">03</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">环球地理 HD</div>
                    <div class="now-prog">非洲荒原巡礼</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">04</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">科学新知 HD</div>
                    <div class="now-prog">星际探索纪元</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">05</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">经典影院 HD</div>
                    <div class="now-prog">名作周末影院</div>
                </div>
            </div>
            <div class="channel-item">
                <span class="pill-num">06</span>
                <div class="logo-box">HD</div>
                <div class="info">
                    <div class="name">都市生活 HD</div>
                    <div class="now-prog">美食寻味之旅</div>
                </div>
            </div>
        </div>
    </div>

    <!-- Program Column -->
    <div class="program-column">
        <div class="date-tabs-bar">
            <div class="date-tab">昨日 11-07</div>
            <div class="date-tab active">今日 11-08 · LIVE</div>
            <div class="date-tab">明日 11-09</div>
        </div>
        <div class="program-list">
            <div class="program-item">
                <span class="program-time">10:00-11:30</span>
                <span class="program-title">非洲荒原：生命奇迹</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item">
                <span class="program-time">11:30-13:00</span>
                <span class="program-title">亚马逊雨林：未解之境</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item">
                <span class="program-time">13:00-15:30</span>
                <span class="program-title">喜马拉雅：冰雪之巅</span>
                <span class="badge-catchup">回看</span>
            </div>
            <div class="program-item current">
                <span class="program-time">15:30-17:30</span>
                <span class="program-title">自然奇观：极地秘境</span>
                <span class="badge-live">● LIVE</span>
            </div>
            <div class="program-item">
                <span class="program-time">17:30-19:00</span>
                <span class="program-title">微观地球：昆虫世界</span>
            </div>
            <div class="program-item">
                <span class="program-time">19:00-20:30</span>
                <span class="program-title">地球脉动：季节轮回</span>
            </div>
        </div>
    </div>
</div>
</body>
</html>
"""
    render_html_to_png(html, os.path.join(SCREENSHOT_DIR, "Channel_list_g.png"))

# ==============================================================================
# 3. playback_hud.png: 播放界面 + 底部单节目时间轴 HUD (极光夜空背景)
# ==============================================================================
def generate_playback_hud():
    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
{COMMON_CSS}

/* Unified Bottom HUD: 1840px x 126px */
.bottom-hud {{
    position: absolute;
    bottom: 30px;
    left: 40px;
    right: 40px;
    height: 126px;
    background: linear-gradient(180deg, rgba(20, 28, 40, 0.88), rgba(10, 14, 23, 0.90));
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 20px;
    padding: 12px 24px 10px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    box-shadow: 0 16px 40px rgba(0, 0, 0, 0.75);
    z-index: 5;
}}

/* Row 1: Channel Info & Replay Shortcut */
.hud-row-1 {{
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 26px;
}}
.hud-channel-left {{
    display: flex;
    align-items: center;
    gap: 10px;
}}
.hud-channel-name {{
    font-size: 16px;
    font-weight: 700;
    color: #FFFFFF;
    letter-spacing: 0.5px;
}}
.btn-back-to-live {{
    background: #00E5FF;
    color: #05070A;
    font-size: 12px;
    font-weight: 800;
    padding: 4px 14px;
    border-radius: 14px;
    display: flex;
    align-items: center;
    gap: 4px;
    box-shadow: 0 0 14px rgba(0, 229, 255, 0.45);
}}

/* Row 2: Programme Titles */
.hud-row-2 {{
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
}}
.now-playing {{
    color: #F8FAFC;
    font-weight: 500;
}}
.now-playing b {{
    color: #CBD5E1;
    font-weight: 400;
    margin-right: 4px;
}}
.next-playing {{
    color: #94A3B8;
    font-size: 12px;
}}

/* Row 3: Single Programme Scrubbable Timeline */
.timeline-track {{
    height: 6px;
    background: rgba(255, 255, 255, 0.15);
    border-radius: 3px;
    position: relative;
    margin: 4px 0;
}}
.timeline-fill {{
    position: absolute;
    top: 0;
    left: 0;
    bottom: 0;
    width: 62%;
    background: linear-gradient(90deg, #00B0FF, #00E5FF);
    border-radius: 3px;
    box-shadow: 0 0 10px rgba(0, 229, 255, 0.55);
}}
.timeline-thumb {{
    position: absolute;
    top: 50%;
    left: 62%;
    transform: translate(-50%, -50%);
    width: 14px;
    height: 14px;
    background: #FFFFFF;
    border: 2px solid #00E5FF;
    border-radius: 50%;
    box-shadow: 0 0 8px #00E5FF;
}}
.timeline-live-edge {{
    position: absolute;
    top: -2px;
    right: 0;
    width: 3px;
    height: 10px;
    background: #FF7A00;
    border-radius: 2px;
}}

/* Row 4: Time Labels & Fixed Behind-Live Delta */
.hud-row-4 {{
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    font-family: monospace;
    font-weight: 700;
}}
.time-start, .time-end {{
    color: #94A3B8;
}}
.hud-center-time {{
    display: flex;
    align-items: center;
    gap: 10px;
}}
.target-time {{
    color: #00E5FF;
    font-size: 12px;
}}
.badge-behind {{
    background: rgba(255, 122, 0, 0.20);
    border: 1px solid rgba(255, 122, 0, 0.55);
    color: #FF7A00;
    padding: 1px 8px;
    border-radius: 6px;
    font-size: 11px;
}}
</style>
</head>
<body>
<div class="video-canvas" style="background-image: url('file://{BG_AURORA}');">
    <div class="video-overlay"></div>
    <div class="watermark">
        <div class="watermark-dot"></div>
        LiteTV · 回看时移模式
    </div>
</div>

<div class="bottom-hud">
    <!-- Row 1: Channel Info & Replay Shortcut -->
    <div class="hud-row-1">
        <div class="hud-channel-left">
            <span class="pill-num">03</span>
            <div class="logo-box" style="border-color:#00E5FF; color:#00E5FF;">4K</div>
            <span class="hud-channel-name">纪录探索 4K</span>
        </div>
        <div class="btn-back-to-live">
            回到直播
        </div>
    </div>

    <!-- Row 2: Programme Titles -->
    <div class="hud-row-2">
        <div class="now-playing">
            <b>正在播放:</b> 自然奇观：极地秘境
        </div>
        <div class="next-playing">
            下一个: 微观地球：昆虫世界
        </div>
    </div>

    <!-- Row 3: Single-programme Timeline Track -->
    <div class="timeline-track">
        <div class="timeline-fill"></div>
        <div class="timeline-thumb"></div>
        <div class="timeline-live-edge"></div>
    </div>

    <!-- Row 4: Time Markers & Behind Live -->
    <div class="hud-row-4">
        <span class="time-start">15:30</span>
        <div class="hud-center-time">
            <span class="target-time">16:44</span>
            <span class="badge-behind">慢于直播 -45:00</span>
        </div>
        <span class="time-end">17:30</span>
    </div>
</div>
</body>
</html>
"""
    render_html_to_png(html, os.path.join(SCREENSHOT_DIR, "playback_hud.png"))

# ==============================================================================
# 4. config.png: 右侧设置面板 (MENU 呼出) + 地球太空背景
# ==============================================================================
def generate_config():
    html = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
{COMMON_CSS}

/* Settings Slide-in Panel: 480px */
.settings-panel {{
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: 480px;
    background: rgba(13, 17, 26, 0.95);
    border-left: 1px solid rgba(255, 255, 255, 0.12);
    box-shadow: -16px 0 36px rgba(0, 0, 0, 0.7);
    padding: 32px 28px;
    display: flex;
    flex-direction: column;
    gap: 20px;
    z-index: 5;
}}
.settings-title {{
    font-size: 20px;
    font-weight: 700;
    color: #FFFFFF;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 12px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}}
.settings-version {{
    font-size: 11px;
    color: #64748B;
    background: rgba(255, 255, 255, 0.06);
    padding: 2px 8px;
    border-radius: 6px;
    font-family: monospace;
}}

.setting-item {{
    display: flex;
    flex-direction: column;
    gap: 8px;
}}
.setting-label {{
    font-size: 13px;
    font-weight: 600;
    color: #94A3B8;
}}
.input-pill {{
    height: 44px;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 10px;
    padding: 0 14px;
    font-size: 13px;
    color: #E2E8F0;
    display: flex;
    align-items: center;
    font-family: monospace;
}}
.input-pill.focused {{
    background: rgba(0, 229, 255, 0.06);
    border: 1.5px solid #00E5FF;
    color: #00E5FF;
    box-shadow: 0 0 12px rgba(0, 229, 255, 0.25);
}}

.radio-group {{
    display: flex;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.10);
    border-radius: 10px;
    padding: 3px;
    gap: 2px;
}}
.radio-chip {{
    flex: 1;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    color: #94A3B8;
    border-radius: 7px;
    font-weight: 500;
}}
.radio-chip.selected {{
    background: rgba(0, 229, 255, 0.18);
    border: 1px solid #00E5FF;
    color: #00E5FF;
    font-weight: 700;
}}

.switch-row {{
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 46px;
    padding: 0 14px;
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 10px;
}}
.switch-label {{
    font-size: 13px;
    color: #E2E8F0;
    font-weight: 500;
}}
.toggle-on {{
    width: 44px;
    height: 24px;
    background: #00E5FF;
    border-radius: 12px;
    position: relative;
    box-shadow: 0 0 10px rgba(0, 229, 255, 0.4);
}}
.toggle-thumb {{
    position: absolute;
    top: 2px;
    right: 2px;
    width: 20px;
    height: 20px;
    background: #05070B;
    border-radius: 50%;
}}
</style>
</head>
<body>
<div class="video-canvas" style="background-image: url('file://{BG_EARTH}');">
    <div class="video-overlay"></div>
    <div class="watermark">
        <div class="watermark-dot"></div>
        LiteTV · 探索频道 HD
    </div>
</div>

<!-- Slide-in Settings Panel -->
<div class="settings-panel">
    <div class="settings-title">
        <span>LiteTV 设置</span>
        <span class="settings-version">v1.6.1</span>
    </div>

    <div class="setting-item">
        <span class="setting-label">M3U 播放列表地址</span>
        <div class="input-pill focused">http://example.com/live.m3u</div>
    </div>

    <div class="setting-item">
        <span class="setting-label">EPG 节目单地址 (XMLTV / DIYP)</span>
        <div class="input-pill">http://example.com/epg.xml.gz</div>
    </div>

    <div class="switch-row">
        <span class="switch-label">同名频道自动合并 (Failover)</span>
        <div class="toggle-on"><div class="toggle-thumb"></div></div>
    </div>

    <div class="setting-item">
        <span class="setting-label">频道列表背景透明度</span>
        <div class="radio-group">
            <div class="radio-chip">半透明</div>
            <div class="radio-chip selected">半不透明</div>
            <div class="radio-chip">不透明</div>
        </div>
    </div>

    <div class="setting-item">
        <span class="setting-label">画面渲染模式</span>
        <div class="radio-group">
            <div class="radio-chip selected">SurfaceView (推荐)</div>
            <div class="radio-chip">TextureView</div>
        </div>
    </div>

    <div class="switch-row">
        <span class="switch-label">Web 局域网配置服务 (8899)</span>
        <div class="toggle-on"><div class="toggle-thumb"></div></div>
    </div>
</div>
</body>
</html>
"""
    render_html_to_png(html, os.path.join(SCREENSHOT_DIR, "config.png"))

if __name__ == "__main__":
    generate_channel_list()
    generate_channel_list_grouped()
    generate_playback_hud()
    generate_config()
    print("All showcase screenshots with public domain photo backgrounds successfully generated!")
