 
!function($){
$(function(){
  var gf_lifetime = {
    flg_animate : false,
    flg_init : false,
     
    lists : [
       3,
       5,
       7,
       14,
        
       30,
       60,
       100
        
    ],
    init : function () {
       
      this.set_marks();
      this.animate();
      this.flg_init = true;
    },
    sort_asc : function (){
       this.lists = this.lists.sort(function(a,b){
         if( a < b ) return -1;
         if( a > b ) return 1;
         return 0;
       });
     },
    get_last_val : function(){
      return this.lists[this.lists.length-1];
    },
    get_percent : function (i_total_cnt, i_current_num){
        var i_percent = Math.floor(100/i_total_cnt);
        if (i_total_cnt === (i_current_num + 1)) {
             
            return 100 - (i_percent * i_current_num);
        } else {
          return i_percent;
        }
    },
    get_margin : function (i_next_key) {
      var i_margin = 0;
      if( isNaN(i_next_key) || i_next_key === 0 ){
        return i_margin;
      }
      var i_max_width = $('#lifetime_divisions').width();
      var i_div_num = $('#lifetime_divisions li').length;
      for (var i=i_div_num;i>i_next_key;--i) {
          if( $('#lifetime_divisions li').eq(i-1)[0] ){
              var $tmp =  $('#lifetime_divisions li').eq(i-1);
              i_margin = i_margin + $tmp[0].getBoundingClientRect().width;
          }
      }
      return i_max_width - i_margin;
    },

    set_marks : function(){
      if( ! $('#lifetime_divisions')[0] ){
        return false;
      }
      var $target = $('#lifetime_divisions');
      $target.find('li').remove();
      var i_cnt = this.lists.length;
      if( i_cnt > 0 ){
        i_current = 0;
        for(var i=0;i < i_cnt;++i){
          var i_tmp = this.get_percent(i_cnt, i);
          var i_percent = i_tmp - i_current;
          var s_percent = i_percent.toString() + '%';
           
          var s_style = '';
          if( this.lists[i] === this.get_last_val() ){   
            s_style = 'style="border-right:none;"';
          }
          $('<li ' + s_style + ' data-lifetime-val="' + this.lists[i].toString() + '">')
            .css('width',s_percent)
            .html('<span>' + this.lists[i].toString() + '日</span>')
            .appendTo($target)
          ;
        }
      }
    },
    animate : function(i_index){
      if( gf_lifetime.flg_animate ){
        return;
      }
      gf_lifetime.flg_animate = true;
      
      var i_next_key = 1;   
      var i_speed = 500;
      
      if( this.flg_init ){   
         
        if( isNaN(i_index) || ! $('#lifetime_divisions li').eq(i_index)[0]){
          var i_current_lifetime = parseInt($('#file_lifetime').val(),10);
          var i_last_val = this.get_last_val();
          var i_key = $.inArray(i_current_lifetime,this.lists);

          if( i_key === -1 ){
            gf_lifetime.flg_animate = false;
            return;
          }

           
          if (i_current_lifetime === i_last_val) {
             i_next_key = 0;
          }

          if( !! this.lists[i_key + 1] ){
            i_next_key = i_key + 1;
          }
        }else{
          i_next_key = i_index;
        }
      }else{
        i_speed = 0;
      }
      var i_percent = this.get_percent(this.lists.length, i_next_key);
      var s_percent = i_percent.toString() + '%';
      $('#lifetime_text').text(this.lists[i_next_key].toString() + '日');
      $('#file_lifetime').val(this.lists[i_next_key]);
      
      var $next = $('#lifetime_divisions li').eq(i_next_key);
      var i_next_width = parseInt($next.css('width').replace(/px/g,''),10);
      var i_margin = gf_lifetime.get_margin(i_next_key);
      $('#lifetime_meter').animate({
         
        'width' : s_percent,
        'margin-left' : i_margin + 'px'
      },i_speed,function(){
        gf_lifetime.flg_animate = false;
      });
    }
  };
  
   
  if( $('#lifetime_meter')[0] ){
    gf_lifetime.init();
     





    $('#lifetime_divisions li').click(function(){
      gf_lifetime.animate($('#lifetime_divisions li').index(this));
      return false;
    });
    $('#lifetime_meter_box-text').click(function(){
      gf_lifetime.animate();
    });
  }
});
}(jQuery);
