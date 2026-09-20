package top.hnwen17.guard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import top.hnwen17.guard.GuardApplication
import top.hnwen17.guard.MainActivity

abstract class BoundFragment<B : ViewBinding>(private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> B) : Fragment() {
    private var _binding: B? = null
    protected val binding: B get() = checkNotNull(_binding)
    protected val model: GuardViewModel by activityViewModels()
    protected val host get() = requireActivity() as MainActivity
    protected val icons get() = (requireActivity().application as GuardApplication).icons
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflate(inflater, container, false)
        return binding.root
    }
    protected fun <T> observe(flow: Flow<T>, render: (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { render(it) } }
        }
    }
    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
