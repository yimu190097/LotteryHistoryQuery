package com.lottery.history.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.lottery.history.model.LotteryType
import com.lottery.history.ui.LotteryFragment

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val types = LotteryType.ALL

    override fun getItemCount(): Int = types.size

    override fun createFragment(position: Int): Fragment {
        return LotteryFragment.newInstance(types[position].code)
    }
}
