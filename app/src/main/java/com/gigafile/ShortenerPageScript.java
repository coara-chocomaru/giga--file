package com.gigafile;

public final class ShortenerPageScript {
    private static final String SCRIPT =
            "(function(){"
            + "'use strict';"
            + "try{"
            + "if(location.protocol!=='https:'||String(location.hostname||'').toLowerCase()!=='shortener.gigafile.nu'||(location.pathname||'/')!=='/')return;"
            + "if(window.__xgfPageCleaner){window.__xgfPageCleaner.run();return;}"
            + "var css='img.my-logo,img[src*=\"/images/top.jpg\"],img[src*=\"/images/logo.png\"],.my-ad-1,.my-ad-2,.my-ad-3,.my-ad-wrapper,.my-ad-overlay,#xgf_ad_overlay_1,[id^=\"im-\"],ins.adsbygoogle,[data-ad-client],[data-ad-slot]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;max-height:0!important;overflow:hidden!important}.my-gigahiko{background-image:none!important}.my-wrap{padding-bottom:16px!important}div.my-toast{bottom:16px!important}.my-wrap>.row.justify-content-center.align-items-center.bg-white>.col-md-4.col-lg-4{flex:0 0 100%!important;max-width:100%!important}';"
            + "function remove(node){try{if(node&&node.parentNode)node.parentNode.removeChild(node);}catch(e){}}"
            + "function removeAll(selector){try{var nodes=document.querySelectorAll(selector);for(var i=nodes.length-1;i>=0;i--)remove(nodes[i]);}catch(e){}}"
            + "function clean(){try{var style=document.getElementById('__xgf_light_style');if(!style){style=document.createElement('style');style.id='__xgf_light_style';style.type='text/css';style.appendChild(document.createTextNode(css));(document.head||document.documentElement).appendChild(style);}"
            + "var fixed=document.querySelectorAll('.fixed-bottom');for(var f=fixed.length-1;f>=0;f--){if(fixed[f].querySelector('.my-ad-overlay,#xgf_ad_overlay_1'))remove(fixed[f]);}"
            + "removeAll('.my-ad-1,.my-ad-wrapper,.my-ad-overlay,#xgf_ad_overlay_1,[id^=\"im-\"],ins.adsbygoogle,[data-ad-client],[data-ad-slot]');"
            + "removeAll('script[src*=\"pagead2.googlesyndication.com\"],script[src*=\"securepubads.g.doubleclick.net\"],script[src*=\"imp-adedge.i-mobile.co.jp\"],script[src*=\"googletagmanager.com/gtag/js\"]');"
            + "var imgs=document.querySelectorAll('img');for(var i=imgs.length-1;i>=0;i--){var image=imgs[i],src=image.getAttribute('src')||'',path='';try{path=new URL(src,location.href).pathname.toLowerCase();}catch(e){}if(path==='/images/top.jpg'||path==='/images/logo.png'){var parent=image.parentNode;remove(image);if(parent&&parent.nodeType===1&&parent.classList){if(parent.classList.contains('navbar-brand'))remove(parent);else if(parent.classList.contains('col-md-6')&&parent.classList.contains('col-lg-5')&&!parent.textContent.trim()&&!parent.querySelector('img,video,canvas,svg'))remove(parent);}}}"
            + "var logo=document.querySelector('.navbar-brand');if(logo&&!logo.querySelector('img,svg'))remove(logo);"
            + "try{if(window.googletag&&window.googletag.cmd)window.googletag.cmd.length=0;}catch(e){}try{if(window.adsbygoogle)window.adsbygoogle.length=0;}catch(e){}try{if(window.adsbyimobile)window.adsbyimobile.length=0;}catch(e){}"
            + "}catch(e){}}"
            + "window.__xgfPageCleaner={run:clean};clean();"
            + "}catch(e){}"
            + "})();";

    private ShortenerPageScript() {
    }

    public static String get() {
        return SCRIPT;
    }
}
