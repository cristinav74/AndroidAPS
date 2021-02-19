package info.nightscout.androidaps.plugins.profile.local

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.tabs.TabLayout
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.dialogs.ProfileSwitchDialog
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.insulin.InsulinOrefBasePlugin.Companion.MIN_DIA
import info.nightscout.androidaps.plugins.profile.local.events.EventLocalProfileChanged
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.localprofile_fragment.*
import java.text.DecimalFormat
import javax.inject.Inject

class LocalProfileFragment : DaggerFragment() {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var localProfilePlugin: LocalProfilePlugin
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var sp: SP

    private var disposable: CompositeDisposable = CompositeDisposable()

    private var basalView: TimeListEdit? = null
    private var spinner: SpinnerHelper? = null

    private val save = Runnable {
        doEdit()
        basalView?.updateLabel(resourceHelper.gs(R.string.basal_label) + ": " + sumLabel())
    }

    private val textWatch = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {}
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            localProfilePlugin.currentProfile()?.dia = SafeParse.stringToDouble(localprofile_dia.text.toString())
            localProfilePlugin.currentProfile()?.name = localprofile_name.text.toString()
            doEdit()
        }
    }

    private fun sumLabel(): String {
        val profile = localProfilePlugin.createProfileStore().getDefaultProfile()
        val sum = profile?.baseBasalSum() ?: 0.0
        return " ∑" + DecimalFormatter.to2Decimal(sum) + resourceHelper.gs(R.string.insulin_unit_shortname)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.localprofile_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabLayout.addTab(tabLayout.newTab().setText(R.string.dia_short))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.ic_short))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.isf_short))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.basal_short))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.target_short))

        processVisibilityOnClick()
        localprofile_dia_placeholder.visibility = View.VISIBLE

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if ( tab.text == getText(R.string.dia_short)) {
                    processVisibilityOnClick()
                    localprofile_dia_placeholder.visibility = View.VISIBLE
                }
                if ( tab.text == getText(R.string.ic_short)) {
                    processVisibilityOnClick()
                    localprofile_ic.visibility = View.VISIBLE
                }
                if ( tab.text == getText(R.string.isf_short)) {
                    processVisibilityOnClick()
                    localprofile_isf.visibility = View.VISIBLE
                }
                if ( tab.text == getText(R.string.basal_short)) {
                    processVisibilityOnClick()
                    localprofile_basal.visibility = View.VISIBLE
                }
                if ( tab.text == getText(R.string.target_short)) {
                    processVisibilityOnClick()
                    localprofile_target.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        fabNewProfile.visibility == View.GONE
        fabCloneProfile.visibility == View.GONE
        fabDeleteProfile.visibility == View.GONE
        fabActivateProfile.visibility == View.GONE

        ViewAnimation.showOut(fabNewProfile)
        ViewAnimation.showOut(fabCloneProfile)
        ViewAnimation.showOut(fabDeleteProfile)
        ViewAnimation.showOut(fabActivateProfile)

        fabMenu.setOnClickListener(clickListener)
        fabNewProfile.setOnClickListener(clickListener)
        fabCloneProfile.setOnClickListener(clickListener)
        fabDeleteProfile.setOnClickListener(clickListener)
        fabActivateProfile.setOnClickListener(clickListener)

        // activate DIA tab
        //processVisibilityOnClick(dia_tab)
        updateGUI("")
        localprofile_dia_placeholder.visibility = View.VISIBLE
    }

    fun build() {
        val pumpDescription = activePlugin.activePump.pumpDescription
        if (localProfilePlugin.numOfProfiles == 0) localProfilePlugin.addNewProfile()
        val currentProfile = localProfilePlugin.currentProfile() ?: return
        val units = if (currentProfile.mgdl) Constants.MGDL else Constants.MMOL

        localprofile_name.removeTextChangedListener(textWatch)
        localprofile_name.setText(currentProfile.name)
        localprofile_name.addTextChangedListener(textWatch)
        localprofile_dia.setParams(currentProfile.dia, hardLimits.minDia(), hardLimits.maxDia(), 0.1, DecimalFormat("0.0"), false, localprofile_save, textWatch)
        localprofile_dia.tag = "LP_DIA"
        TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_ic, "IC", resourceHelper.gs(R.string.ic_label), currentProfile.ic, null, hardLimits.minIC(), hardLimits.maxIC(), 0.1, DecimalFormat("0.0"), save)
        basalView = TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_basal, "BASAL", resourceHelper.gs(R.string.basal_label) + ": " + sumLabel(), currentProfile.basal, null, pumpDescription.basalMinimumRate, 10.0, 0.01, DecimalFormat("0.00"), save)
        if (units == Constants.MGDL) {
            TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_isf, "ISF", resourceHelper.gs(R.string.isf_label), currentProfile.isf, null, hardLimits.MINISF, hardLimits.MAXISF, 1.0, DecimalFormat("0"), save)
            TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_target, "TARGET", resourceHelper.gs(R.string.target_label), currentProfile.targetLow, currentProfile.targetHigh, hardLimits.VERY_HARD_LIMIT_TARGET_BG[0].toDouble(), hardLimits.VERY_HARD_LIMIT_TARGET_BG[1].toDouble(), 1.0, DecimalFormat("0"), save)
        } else {
            TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_isf, "ISF", resourceHelper.gs(R.string.isf_label), currentProfile.isf, null, Profile.fromMgdlToUnits(hardLimits.MINISF, Constants.MMOL), Profile.fromMgdlToUnits(hardLimits.MAXISF, Constants.MMOL), 0.1, DecimalFormat("0.0"), save)
            TimeListEdit(context, aapsLogger, dateUtil, view, R.id.localprofile_target, "TARGET", resourceHelper.gs(R.string.target_label), currentProfile.targetLow, currentProfile.targetHigh, Profile.fromMgdlToUnits(hardLimits.VERY_HARD_LIMIT_TARGET_BG[0].toDouble(), Constants.MMOL), Profile.fromMgdlToUnits(hardLimits.VERY_HARD_LIMIT_TARGET_BG[1].toDouble(), Constants.MMOL), 0.1, DecimalFormat("0.0"), save)
        }

        // Spinner
        spinner = SpinnerHelper(view?.findViewById(R.id.localprofile_spinner))
        val profileList: ArrayList<CharSequence> = localProfilePlugin.profile?.getProfileList()
            ?: ArrayList()
        context?.let { context ->
            val adapter = ArrayAdapter(context, R.layout.spinner_centered, profileList)
            spinner?.adapter = adapter
            spinner?.setSelection(localProfilePlugin.currentProfileIndex)
        } ?: return
        spinner?.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (localProfilePlugin.isEdited) {
                    activity?.let { activity ->
                        OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.doyouwantswitchprofile), {
                            localProfilePlugin.currentProfileIndex = position
                            build()
                        }, {
                            spinner?.setSelection(localProfilePlugin.currentProfileIndex)
                        })
                    }
                } else {
                    localProfilePlugin.currentProfileIndex = position
                    build()
                }
            }
        })


        // this is probably not possible because it leads to invalid profile
        // if (!pumpDescription.isTempBasalCapable) localprofile_basal.visibility = View.GONE

        @Suppress("SetTextI18n")
        localprofile_units.text = resourceHelper.gs(R.string.units_colon) + " " + (if (currentProfile.mgdl) resourceHelper.gs(R.string.mgdl) else resourceHelper.gs(R.string.mmol))

        localprofile_reset.setOnClickListener {
            localProfilePlugin.loadSettings()
            build()
        }

        localprofile_save.setOnClickListener {
            if (!localProfilePlugin.isValidEditState()) {
                return@setOnClickListener  //Should not happen as saveButton should not be visible if not valid
            }
            localProfilePlugin.storeSettings(activity)
            build()
        }
        updateGUI("")
    }

    private val clickListener: View.OnClickListener = View.OnClickListener { view ->
        when ( view.id ){
            R.id.fabMenu          -> {
                if ( fabNewProfile.visibility == View.GONE) {
                    ViewAnimation.showIn(fabNewProfile)
                    ViewAnimation.showIn(fabCloneProfile)
                    ViewAnimation.showIn(fabDeleteProfile)
                    updateGUI("onMenue")
                } else if ( fabNewProfile.visibility == View.VISIBLE) {
                    ViewAnimation.showOut(fabNewProfile)
                    ViewAnimation.showOut(fabCloneProfile)
                    ViewAnimation.showOut(fabDeleteProfile)
                    if( fabActivateProfile.visibility == View.VISIBLE ) ViewAnimation.showOut(fabActivateProfile)
                }
            }
            R.id.fabNewProfile -> {
                if (localProfilePlugin.isEdited) {
                    activity?.let { OKDialog.show(it, "", resourceHelper.gs(R.string.saveorresetchangesfirst), null, sp) }
                } else {
                    localProfilePlugin.addNewProfile()
                    build()
                }
                ViewAnimation.showOut(fabNewProfile)
                ViewAnimation.showOut(fabCloneProfile)
                ViewAnimation.showOut(fabDeleteProfile)
                if( fabActivateProfile.visibility == View.VISIBLE )  ViewAnimation.showOut(fabActivateProfile)
            }
            R.id.fabCloneProfile          -> {
                if (localProfilePlugin.isEdited) {
                    activity?.let { OKDialog.show(it, "", resourceHelper.gs(R.string.saveorresetchangesfirst), null, sp) }
                } else {
                    localProfilePlugin.cloneProfile()
                    build()
                }
                ViewAnimation.showOut(fabNewProfile)
                ViewAnimation.showOut(fabCloneProfile)
                ViewAnimation.showOut(fabDeleteProfile)
                ViewAnimation.showOut(fabActivateProfile)
            }
            R.id.fabDeleteProfile             -> {
                activity?.let { activity ->
                    OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.deletecurrentprofile), Runnable {
                        localProfilePlugin.removeCurrentProfile()
                        build()
                    }, null, sp)
                }
                ViewAnimation.showOut(fabNewProfile)
                ViewAnimation.showOut(fabCloneProfile)
                ViewAnimation.showOut(fabDeleteProfile)
                ViewAnimation.showOut(fabActivateProfile)
            }
            R.id.fabActivateProfile             -> {
                ProfileSwitchDialog()
                    .also { it.arguments = Bundle().also { bundle -> bundle.putInt("profileIndex", localProfilePlugin.currentProfileIndex) } }
                    .show(childFragmentManager, "NewNSTreatmentDialog")
                ViewAnimation.showOut(fabNewProfile)
                ViewAnimation.showOut(fabCloneProfile)
                ViewAnimation.showOut(fabDeleteProfile)
                ViewAnimation.showOut(fabActivateProfile)
            }

        }

    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventLocalProfileChanged::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ build() }, { fabricPrivacy.logException(it) }
            )
        build()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    fun doEdit() {
        localProfilePlugin.isEdited = true
        updateGUI("")
    }

    fun updateGUI(calledFrom : String) {
        val isValid = localProfilePlugin.isValidEditState()
        val isEdited = localProfilePlugin.isEdited
        if (isValid) {
            this.view?.setBackgroundColor(resourceHelper.gc(R.color.transparent))

            if (isEdited) {
                //edited profile -> save first
                if ( fabActivateProfile.visibility ==  View.VISIBLE ) ViewAnimation.showOut(fabActivateProfile)
                localprofile_save.visibility = View.VISIBLE
            } else {
                if ( calledFrom == "onMenue" )   ViewAnimation.showIn(fabActivateProfile)
                localprofile_save.visibility = View.GONE
            }
        } else {
            this.view?.setBackgroundColor(resourceHelper.gc(R.color.error_background))
            if ( calledFrom == "" )   fabActivateProfile.visibility =  View.GONE
            localprofile_save.visibility = View.GONE //don't save an invalid profile
        }

        //Show reset button if data was edited
        if (isEdited) {
            localprofile_reset.visibility = View.VISIBLE
        } else {
            localprofile_reset.visibility = View.GONE
        }
    }

    private fun processVisibilityOnClick() {
        localprofile_dia_placeholder.visibility = View.GONE
        localprofile_ic.visibility = View.GONE
        localprofile_isf.visibility = View.GONE
        localprofile_basal.visibility = View.GONE
        localprofile_target.visibility = View.GONE
    }
}
