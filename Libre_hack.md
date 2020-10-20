## Disable Libre SMB restrictions

AndroidAPS 2.7

app/src/main/java/info/nightscout/androidaps/plugins/constraints/safety/SafetyPlugin.java  
line 151 to 159
```
   @NonNull @Override
    public Constraint<Boolean> isAdvancedFilteringEnabled(@NonNull Constraint<Boolean> value) {
        BgSourceInterface bgSource = activePlugin.getActiveBgSource();

//        if (!bgSource.advancedFilteringSupported())
//            value.set(getAapsLogger(), false, getResourceHelper().gs(R.string.smbalwaysdisabled), this);
        value.set(getAapsLogger(), true, getResourceHelper().gs(R.string.disable_Libre_smb_restrictions), this);
        return value;
    }
```

app/src/main/res/values/strings.xml  
add following line:
```
<string name="disable_Libre_smb_restrictions">Filtering active, Go SMB!</string>
```
