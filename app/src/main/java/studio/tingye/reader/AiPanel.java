package studio.tingye.reader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AiPanel {
  private final WordActivity a;
  private final ExecutorService requests=Executors.newSingleThreadExecutor();
  private final android.util.LruCache<String,String> translated=new android.util.LruCache<>(48);
  private int generation;
  private String back="settings";
  private EditText source;
  private TextView result;
  private boolean translating;
  private String draft="";

  AiPanel(WordActivity activity) { a=activity; }
  private SharedPreferences prefs() { return a.getPreferences(0); }

  void open(String from) { back=from; a.show("ai_settings"); }
  void back() { a.show(back); }
  void leave() {
    if(a.screen.equals("ai_translate") && source!=null) draft=source.getText().toString();
    generation++;
    translating=false;
    LocalLanguageModel.cancel();
  }

  void settings() {
    a.gap(a.body,12);
    a.section("手机模型","Qwen2.5 · 0.5B");
    a.settingSwitch("启用手机 AI","可在离线时生成短建议和试译俄语句子。首次启用后使用时会准备模型文件。",
        LocalLanguageModel.ENABLED);
    a.gap(a.body,15);
    a.section("自带 API","");
    CloudAi.Provider provider=CloudAi.selected(prefs());
    String model=CloudAi.model(prefs(),provider);
    a.row(a.body,provider.title,model.isEmpty()?"选择服务商":"服务商 · "+model,R.drawable.s_arrow,this::selectProvider);
    a.row(a.body,CloudAi.configured(prefs(),provider)?"更换 API 密钥":"填写 API 密钥",
        CloudAi.configured(prefs(),provider)?"密钥已加密保存在手机":"填好即可使用",
        R.drawable.s_gear,()->editKey(provider));
    int tokens=prefs().getInt(CloudAi.usageKey(provider),-1);
    if(tokens>=0) {
      a.add(a.body,a.txt("上次使用 "+tokens+" tokens",14,PaperActivity.muted,false),-2);
      a.gap(a.body,5);
    }
    if(provider==CloudAi.Provider.DEEPSEEK && CloudAi.configured(prefs(),provider))
      a.row(a.body,"查看余额","由 DeepSeek 返回",R.drawable.s_arrow,()->balance(provider));
    if(!provider.dashboard.isEmpty())
      a.row(a.body,"用量控制台","查看服务商账单与额度",R.drawable.s_arrow,()->openDashboard(provider));
    if(CloudAi.configured(prefs(),provider))
      a.row(a.body,"移除这把密钥","",R.drawable.s_close,()->new FeedbackDialog(a)
          .setTitle("移除 "+provider.title+" 的密钥？")
          .setNegativeButton("返回",null)
          .setPositiveButton("移除",(d,i)->{CloudAi.clearKey(prefs(),provider);a.show("ai_settings");}).show());
  }

  private void selectProvider() {
    CloudAi.Provider[] options=CloudAi.Provider.values();
    String[] names=new String[options.length];
    for(int i=0;i<options.length;i++)names[i]=options[i].title;
    new FeedbackDialog(a).setTitle("选择 AI 服务")
        .setItems(names,(d,i)->{
          prefs().edit().putString("aiProvider",options[i].name()).apply();
          a.show("ai_settings");
        }).show();
  }

  private void editKey(CloudAi.Provider provider) {
    LinearLayout wrap=a.box(true);
    a.pad(wrap,22,9,22,8);
    EditText endpoint=new EditText(a), model=new EditText(a);
    if(provider==CloudAi.Provider.CUSTOM) {
      endpoint.setSingleLine();
      endpoint.setHint("HTTPS 接口地址");
      endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
      endpoint.setText(CloudAi.endpoint(prefs(),provider));
      a.add(wrap,endpoint,49);
      model.setSingleLine();
      model.setHint("模型名称");
      model.setText(CloudAi.model(prefs(),provider));
      a.add(wrap,model,49);
    }
    EditText field=new EditText(a);
    field.setSingleLine();
    field.setHint("API 密钥");
    field.setContentDescription("API 密钥");
    field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
    a.add(wrap,field,52);
    android.app.AlertDialog dialog=new FeedbackDialog(a).setTitle(provider.title+" 密钥")
        .setView(wrap).setNegativeButton("返回",null).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(v->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
      String key=field.getText().toString().trim();
      if(key.isEmpty() || key.matches(".*\\s.*")) {
        field.setError("请输入有效的密钥");
        return;
      }
      String url="",modelName="";
      if(provider==CloudAi.Provider.CUSTOM) {
        try { url=CloudAi.checkedEndpoint(endpoint.getText().toString().trim()); }
        catch(Exception invalid) { endpoint.setError("请输入 https:// 开头的兼容接口地址"); return; }
        modelName=model.getText().toString().trim();
        if(modelName.isEmpty()) { model.setError("请输入模型名"); return; }
      }
      try {
        CloudAi.saveKey(prefs(),provider,key,url,modelName);
        field.setText("");
        dialog.dismiss();
        a.show("ai_settings");
      } catch(Exception problem) {
        field.setError("这台设备暂时无法保存密钥");
      }
    }));
    dialog.show();
  }

  private void balance(CloudAi.Provider provider) {
    int id=++generation;
    a.tip("正在查询余额");
    requests.execute(()->{
      String message;
      try { message=CloudAi.balance(prefs(),provider); }
      catch(Exception error) { message=error instanceof java.io.IOException?error.getMessage():"暂时无法查询余额"; }
      String value=message;
      a.ui.post(()->{
        if(id!=generation || !a.screen.equals("ai_settings") || a.isDestroyed())return;
        new FeedbackDialog(a).setTitle("DeepSeek 余额").setMessage(value).setPositiveButton("关闭",null).show();
      });
    });
  }

  private void openDashboard(CloudAi.Provider provider) {
    try { a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(provider.dashboard))); }
    catch(android.content.ActivityNotFoundException error) { a.tip("此设备没有浏览器"); }
  }

  void translation() {
    a.gap(a.body,12);
    a.section("俄语译句","");
    source=new EditText(a);
    source.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    source.setGravity(android.view.Gravity.TOP);
    source.setMinLines(3);
    source.setHint("粘贴一小段俄语");
    source.setContentDescription("俄语原文");
    source.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
    source.setText(draft);
    a.add(a.body,source,-2);
    a.gap(a.body,9);
    a.add(a.body,a.action("用手机翻译",0,true,()->translate(false)),-2);
    a.gap(a.body,6);
    a.add(a.body,a.action("用我的 API 翻译",0,false,()->translate(true)),-2);
    a.add(a.body,a.txt("使用 API 时，原句会发给所选服务商。",13,PaperActivity.muted,false),-2);
    a.gap(a.body,16);
    result=a.txt("",17,PaperActivity.ink,false);
    result.setTextIsSelectable(true);
    a.add(a.body,result,-2);
    a.gap(a.body,9);
    a.row(a.body,"AI 设置","手机模型与 API 服务",R.drawable.s_gear,()->open("ai_translate"));
  }

  private void translate(boolean cloud) {
    if(translating)return;
    String input=source.getText().toString().trim();
    if(input.isEmpty()) { source.setError("请先输入俄语"); return; }
    CloudAi.Provider provider=CloudAi.selected(prefs());
    if(cloud && !CloudAi.configured(prefs(),provider)) {
      a.tip("请先填写 API 密钥");
      return;
    }
    int id=++generation;
    translating=true;
    a.hideKeys();
    result.setText(cloud?"正在联系 "+provider.title+"…":"正在查本地词典和句库…");
    requests.execute(()->{
      String display;
      String instruction="把给定的俄语译成简体中文。只输出中文译文，不添加解释。";
      try {
        if(cloud) display=provider.title+" · 译文\n"+CloudAi.complete(prefs(),provider,instruction,input).text;
        else display=offline(input,id);
      } catch(Exception problem) {
        display=cloud?"暂未收到译文。可先用手机翻译。":"这句暂时没译出，可试着拆成短句。";
      }
      String value=display;
      a.ui.post(()->{
        if(id!=generation || !a.screen.equals("ai_translate") || a.isDestroyed())return;
        translating=false;
        result.setText(value);
      });
    });
  }

  private String offline(String input,int id) throws Exception {
    String exact=a.translations.find(input);
    if(!exact.isEmpty())return "离线 · 译文\n"+exact;
    if(!LocalLanguageModel.enabled(prefs())) {
      String meanings=a.translations.hints(input);
      return meanings.isEmpty()?"离线词库里没有匹配项。可开启手机 AI 试译。"
          :"词义参考\n"+meanings+"\n开启手机 AI 可翻译整句。";
    }
    String already=translated.get(input);
    if(already!=null)return already;
    a.ui.post(()->{
      if(id==generation && a.screen.equals("ai_translate"))result.setText("手机正在翻译…");
    });
    String generated="手机 AI · 译文\n"+a.translations.infer(input);
    translated.put(input,generated);
    return generated;
  }


  void close() { leave(); requests.shutdownNow(); }
}
