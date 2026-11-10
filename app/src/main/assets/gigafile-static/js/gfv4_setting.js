$(function () {
    $.ajaxSetup({ cache: false });

     
    var ua = window.navigator.userAgent.toLowerCase();

    var i_flg_sp = false;

     
    if (chk_sp(ua)) { i_flg_sp = true; }
    ajax_get_news();
    move_explain();
    focus_sample_mail_text();
    onfocus_all_select(i_flg_sp);
    click_send_mail();
    set_default_value();
    click_tutorial();
    move_tutorial();

     
    var rightBnrDefaultCss = {
        'position': 'absolute',
        'top': 35 + 'px',
        'right': 30 + 'px',
        'width': 160 + 'px',
        'height': 600 + 'px'
    };

     
     
     
     
     
     

     
     
     
     

     
     
    function fitFloating($bnr, defaultCss, $fitTarget) {
        var scrollTop = $(document).scrollTop();
        var bnrDefaultTop = defaultCss.top.replace('px', '');
        var fit = {
            top: $fitTarget.offset().top,
            height: $fitTarget.height()
        }

         
        if ($bnr.height() > fit.height) {
            $bnr.css(defaultCss);
            return;
        }

         
        if (scrollTop < fit.top) {
            $bnr.css(defaultCss);
            return;
        }

         
         
        var sabunTop = scrollTop - fit.top;
        if (sabunTop < bnrDefaultTop) {
            $bnr.css(defaultCss);
            return;
        } else {
            var totalHeight = sabunTop + $bnr.height();
            if (totalHeight < fit.height) {
                $bnr.css({ 'top': sabunTop + 'px' });
            } else {
                 
                $bnr.css({ 'top': (fit.height - $bnr.height()) + 'px' });
            }
        }
    }

     
    if (runtimes === "flash") {
        $("<p style=\"background:#FFF;font-size:80%;padding:5px;color:red;margin:10px;\">")
            .html('お使いのブラウザでは正常にファイルをアップロードできない可能性があります。<br />お手数ですがChromeもしくはFirefoxの最新版をお使いください。')
            .prependTo('#file_list')
            ;
    }

     
    function adBlockDetected() {
        $.ajax({
            type: "POST",
            cache: false,
            url: "./add_count_fab_jx.php",
            timeout: 1000 * 10
        });

        if (typeof gtag !== 'undefined') {
            gtag('event', 'enableAB', {
                'event_category': 'FAB',
                'event_label': 'firstload',
                'value': 1,
                'non_interaction': true
            });
        }
    }

    function adBlockNotDetected() {
    }

     








    window.addEventListener('load', function () {
        const adSlotId = '#wrap_banner_billboard';
        const adSlot = document.querySelector(adSlotId);
        try {
            if (adSlot.clientHeight === 0) {
                adBlockDetected();
            }
        } catch (e) {
        }
    });

     
    $('#contents_header').on('click touchend', 'li', function () {
        count_tabClicked($(this));
        change_tab_contents($(this));
    });

    $('.guide_area').hover(function () {
        $('#contents_header').find('.tab_config').addClass('guide_hvr');
    }, function () {
        $('#contents_header').find('.tab_config').removeClass('guide_hvr');
    });
});

function change_tab_contents($elm) {
    var s_tag = $elm.attr('data-tag');
    if (s_tag !== '' && $('#contents_' + s_tag)[0]) {
        if ($elm.hasClass('header_selected')) {
            return;
        }
        var s_color = $elm.attr('data-color');
        $('#contents_header').css('border-color', $elm.attr('data-color'));

        $('#contents_header li').removeClass('header_selected');
        $elm.addClass('header_selected');
        $('#contents_box .contents_body').addClass('conceal');
        $('#contents_' + s_tag).removeClass('conceal');
    }
}

function count_tabClicked($elm) {
    if (!$elm.hasClass('sendCount') || $elm.hasClass('header_selected')) {
        return;
    }
    var s_tag = $elm.attr('data-tag');
    if (s_tag === undefined || s_tag === '') {
        return;
    }
    if (typeof gtag !== 'undefined') {
        gtag('event', 'tabClicked', {
            'event_category': 'gigafile.nu',
            'event_label': s_tag,
            'value': 1,
            'non_interaction': true
        });
    }
}

function change_potition_bnr(i_left) {
    var i_scroll = $(document).scrollTop();
    if (!$("#wrap_banner_billboard")[0]) {
        return;
    }
    var i_h = $("#wrap_banner_billboard").offset().top + $("#wrap_banner_billboard").height() + 30;
    if (i_scroll < i_h) {
        $("#bnr_float").css({
            "position": "absolute"
            , "top": "36px"
            , "left": "0px"
        });
    }

    if (i_scroll >= i_h) {
        $("#bnr_float").css({
            "position": "fixed"
            , "top": "0px"
            , "left": i_left + "px"
        });
    }
}

function get_left_position() {
    var w = $(window).width();
    var i_panel_w = $('#upload_panel').width();
    var i = (w - i_panel_w) / 2;
    if (i <= 0) {
        i = 0;
    }
    return i;
}

function open_explain() {
     
     
    $("#explain_title").click(function () { $(this).hide(); $("#explain_images").show(); });
    $("#explain_images").click(function () { $(this).hide(); $("#explain_title").show(); });
}

function move_explain() {
     
    $("#explain_title").click(function () {
        var s_tgt = "#explain_images";
        if (s_tgt !== undefined) {
            var r_tgt_offset = $(s_tgt).offset();
            var s_tgt_top = r_tgt_offset.top;
            $('html, body').animate({ scrollTop: s_tgt_top }, 500);
        }
    });
}

function ajax_get_news() {
     
     
    $.getJSON("get_news_jx.php", function (json, t, x) {
        $(".news_title").html(json.title);
        $("#news_body").html(json.list);
    });
}

function focus_sample_mail_text() {
    var s_bg_path = "url(https://src.gigafile.nu/images/bg_input_text.gif) no-repeat";
    var s_white = "#FFF";

     
    $("#mail_addr").focus(function () { change_background(this); });

     
    $("#mail_addr").blur(function () {
        var isu = void 0;
        var s_txt = $(this).val();
        var s_bg = s_bg_path;

        if (s_txt != isu) { if (s_txt != "") { s_bg = s_white; } }
         
        change_background(this, s_bg);
    });
}

function change_background(elm, option) {
     
    var isu = void 0;

    if (option == isu) {
        option = "#FFF";
    } else {
        if (option == "") { option = "#FFF"; }
    }

    $(elm).css("background", option);
}

 
function onfocus_all_select(i_flg_sp) {
    $(document).on("focus", "#file_list .url,#file_list .delkey,#file_list .dlkey_inp", function () {
         
        var o_this = $(this);
        setTimeout(function () {
            if (o_this.is(":focus")) {
                if (i_flg_sp) {
                    o_this.get(0).setSelectionRange(0, 9999);
                } else {
                    o_this.select();
                }
            }
        }, 0);
    });
}

function set_default_value() {
     
    $(document).on("change", "#file_list .url,#file_list .delkey", function () {
        $(this).val($(this).attr("origin"));
    });
}

 


function click_send_mail() {
    $("#send_mail,#send_email").click(function () {
        if (!is_checked_addr_owner()) {
            alert('メール機能を利用するには、利用規約への同意が必要です。');
            return;
        }
        mail();
    });
}

$(function () {
    if ($("#approval_control")[0]) {
        $("#approval_control").hide();

        $("#email_approval").change(function () {
            if ($(this).prop("checked")) {
                $("#approval_control").show();
            } else {
                $("#approval_control").hide();
            }
        });

        $('#email_addr').on('input', function () {
            $('#dlnotify_mail_addr').val($('#email_addr').val());
        });
    }

     
    if ($('#addr_owner_self')[0]) {
        $('#email_addr').on('input', function () {
            $('#dlnotify_mail_addr').val($('#email_addr').val());
        });
    }
});

 
function is_checked_addr_owner () {
     
     
    if ($('#addr_owner_self')[0]) {
      if ( $('[name="addr_owner"]:checked').val() === undefined) {
        return false;
      }
    }
    return true;
}


function chk_sp(ua) {
    if (ua.indexOf('ipad') != -1) {
        return true;
    }

    if (ua.indexOf('iphone') != -1) {
        return true;
    }

    if (ua.indexOf('android') != -1) {
        return true;
    }
    return false;
}

function click_tutorial() {
    $("#tutorial_tab_menu td").click(function () {
        var s_ytb_id = $(this).attr("data-num");
        var s_path = "//www.youtube.com/embed/";
        $("#tutorial_ytb").attr("src", s_path + s_ytb_id);
        $("#tutorial_tab_menu td").removeClass("tuto_on");
        $(this).addClass("tuto_on");
    });
}

function move_tutorial() {
     
    $("#tutorial_title,#nav_tutorial,#nav_tutorial_header").click(function () {
        var s_tgt = "#tutorial_panel";
        if (s_tgt !== undefined) {
            var r_tgt_offset = $(s_tgt).offset();
            var s_tgt_top = r_tgt_offset.top;
            $('html, body').animate({ scrollTop: s_tgt_top }, 500);
        }
    });
}
 
!function ($) {
    $(function () {
        if ($("#savedate")[0]) {
            var i_slide_margin = -65;
            var i_flg_slide = false;
            $("#save_slide_bar").click(function () {
                if (i_flg_slide) {
                    return;
                }
                i_flg_slide = true;
                var i_margin_now = parseInt($(this).css('margin-left').replace(/px/g, ''), 10);
                var i_margin;
                var i_lifetime = 7;   
                if ($(this).hasClass('save_slide_bar-off')) {
                    i_margin = i_margin_now - i_slide_margin;
                    i_lifetime = 14;   
                    $(this).addClass('save_slide_bar-on').removeClass('save_slide_bar-off');
                } else {
                    i_margin = i_margin_now + i_slide_margin;
                    $(this).addClass('save_slide_bar-off').removeClass('save_slide_bar-on');
                }
                $('#file_lifetime').val(i_lifetime);
                $(this).stop(true, true).animate({ marginLeft: i_margin }, 500, function () {
                    i_flg_slide = false;
                });
            });

            $("#save_text").click(function () {
                $("#save_slide_bar").trigger("click");
            });
        }
    });
}(jQuery);

 




$(function () {
    var o_floating_left_banner_default_css = {
        position: $('#bnr_float').css('position'),
        top: $('#bnr_float').css('top'),
        left: $('#bnr_float').css('left'),
        width: $('#bnr_float').css('width'),
        height: $('#bnr_float').css('height'),
    };
    if ($("#bnr_float")[0]) {
        var $left_float_banner = $("#bnr_float");
        var o_default_pos = $left_float_banner.offset();

        $(window).resize(function (e) {
            set_float_pos(
                $left_float_banner,
                o_floating_left_banner_default_css,
                o_default_pos.top
            );
        });
        $(window).scroll(function (e) {
            set_float_pos(
                $left_float_banner,
                o_floating_left_banner_default_css,
                o_default_pos.top
            );
        });
    }
});

function get_fixed_left_pos() {
    var i_scroll_left = $(document).scrollLeft();
    var i_remaining_w = document.documentElement.scrollWidth - $('#upload_panel').width();
    if (i_scroll_left !== 0) {
        i_remaining_w = i_remaining_w - i_scroll_left;
    }
    if (i_remaining_w > 0) {
        return Math.round(i_remaining_w / 2);
    } else {
        return i_remaining_w;
    }
}

function get_require_height_for_float_area(s_height) {
    var i_height = parseInt(s_height.replace(/px$/ig, ''), 10);
    if ($('#geniee_overlay_outer')[0]) {
        i_height = i_height + $('#geniee_overlay_outer').height();
        i_height = i_height + 2;   
    }
    return i_height;
}

function set_float_pos($target, o_default_css, i_default_top) {
    var i_scroll_top = $(document).scrollTop();
     
     
    var i_remaining_h = document.documentElement.scrollHeight - i_scroll_top;
    if (
        i_scroll_top < i_default_top
        || i_scroll_top < i_default_top
    ) {
        $target.css({
            position: o_default_css.position,
            top: o_default_css.top,
            left: o_default_css.left,
            width: o_default_css.width,
            height: o_default_css.height
        });
    } else if (i_scroll_top >= i_default_top) {
        var i_overflow_h = (i_remaining_h - get_require_height_for_float_area(o_default_css.height));
        var s_top = '0px';
        if (i_overflow_h < 0) {
            var s_top = i_overflow_h.toString() + 'px';
        }
        $target.css({
            position: 'fixed',
            top: s_top,
            left: get_fixed_left_pos().toString() + 'px',
            width: o_default_css.width,
            height: o_default_css.height
        });

    }
}
