document.addEventListener("DOMContentLoaded", () => {
    const qrBtn = document.querySelector('a[id^="social-qrlogin"]');
    if (!qrBtn) return;

    qrBtn.addEventListener("click", async (e) => {
        e.preventDefault();

        const res = await fetch(qrBtn.href, {method: "POST"}).then(res => res.text());
        if (!res) return;

        const json = JSON.parse(res);
        if (!json.qr_session || !json.kc_session) return;

        const {qr_image_data, ttl, statusUrl, interval} = json;

        // 创建遮罩与弹窗
        const mask = document.createElement("div");
        mask.style = `
            position:fixed;top:0;left:0;right:0;bottom:0;
            background:rgba(0,0,0,0.45);backdrop-filter:blur(3px);
            z-index:9998;
        `;

        const box = document.createElement("div");
        box.style = `
            position:fixed;left:50%;top:50%;
            transform:translate(-50%,-50%);
            width:380px;padding:24px;border-radius:14px;
            background:#fff;z-index:9999;
            box-shadow:0 6px 30px rgba(0,0,0,0.25);
            text-align:center;font-family:-apple-system,Segoe UI,Roboto,sans-serif;
        `;

        box.innerHTML = `
            <h3 style="margin:0 0 12px;color:#222;">扫码登录易识IT账号</h3>
            <img src="${qr_image_data}" width="240" height="240" style="border:1px solid #eee;border-radius:8px;" />
            <p id="qr-tip" style="font-size:14px;color:#555;margin-top:10px;">请使用易识IT App 扫描二维码以继续登录</p>
            <p id="qr-count" style="font-size:12px;color:#888;margin-top:4px;">二维码将在 ${ttl} 秒后失效</p>
            <button id="qr-close" style="margin-top:14px;padding:6px 20px;border:none;background:#6c757d;color:white;border-radius:6px;cursor:pointer;">取消</button>
        `;
        document.body.append(mask, box);

        const tip = document.getElementById("qr-tip");
        const count = document.getElementById("qr-count");
        const btn = document.getElementById("qr-close");

        const closeModal = () => {
            mask.remove();
            box.remove();
            window.location.reload();
        };
        mask.onclick = closeModal;
        btn.onclick = closeModal;

        // 记录起始时间和结束时间
        const startTime = Date.now();
        const endTime = startTime + (ttl * 1000);
        let expired = false;

        const poll = async () => {
            if (expired) return;
            try {
                const resp = await fetch(`${statusUrl}${statusUrl.includes('?') ? '&' : '?'}timestamp=${Math.floor(Date.now() / 1000)}`);
                // 检查HTTP状态码，处理404等情况
                if (!resp.ok) {
                    if (resp.status === 404) {
                        // 处理404错误，视为二维码失效
                        handleQRCodeExpired();
                        return;
                    } else {
                        throw new Error(`HTTP error! status: ${resp.status}`);
                    }
                }

                const data = await resp.json();


                switch (data.status) {
                    case "CONFIRMED":
                        const url = data.url;

                        tip.innerText = "身份验证通过，正在跳转...";
                        count.style.display = "none";
                        btn.disabled = true;
                        btn.style.background = "#28a745";
                        setTimeout(() => (window.location.href = url), 600);
                        return;
                    case "SCANNED":
                        tip.innerText = "二维码已扫描，请在手机端确认登录";
                        break;
                    case "PENDING":
                        tip.innerText = "等待扫描，请打开易识IT App 扫描二维码";
                        break;
                    case "EXPIRED":
                        expired = true;
                        tip.innerText = "二维码已失效，请重新开始登录流程";
                        count.style.display = "none";
                        btn.innerText = "重新开始";
                        btn.style.background = "#007bff";
                        btn.onclick = () => {
                            mask.remove();
                            box.remove();
                            qrBtn.click();
                        };
                        return;
                    default:
                        tip.innerText = "正在等待响应，请稍候...";
                }

                // 基于实际时间计算剩余时间
                const now = Date.now();
                const remaining = Math.max(0, endTime - now) / 1000;

                if (remaining > 0) {
                    count.innerText = `二维码将在 ${Math.round(remaining)} 秒后失效`;
                    setTimeout(poll, interval);
                } else {
                    expired = true;
                    tip.innerText = "二维码已失效，请重新开始登录流程";
                    count.style.display = "none";
                    btn.innerText = "重新开始";
                    btn.style.background = "#007bff";
                    btn.onclick = () => {
                        mask.remove();
                        box.remove();
                        qrBtn.click();
                    };
                }
            } catch (err) {
                tip.innerText = "网络异常，请检查连接后重试";
                count.style.display = "none";
                console.error(err);
            }
        };
        poll();
        const handleQRCodeExpired = () => {
            expired = true;
            tip.innerText = "二维码已失效，请重新开始登录流程";
            tip.style.color = "#dc3545";
            count.style.display = "none";

            // 模糊二维码图像
            const qrImage = box.querySelector('img');
            if (qrImage) {
                qrImage.style.filter = "blur(4px)";
            }

            btn.innerText = "重新开始";
            btn.style.background = "#007bff";
            btn.onclick = () => {
                window.location.reload();
            };
        };
    });

});
